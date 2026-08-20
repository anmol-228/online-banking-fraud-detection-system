# Testing

## Approach

Tests were written alongside each module rather than afterwards, at four levels, plus a fifth where
the whole system is run and exercised over real HTTP.

| Level | What it checks | Tooling |
|---|---|---|
| **Unit** | One class in isolation | JUnit 5 + Mockito |
| **Integration** | Controller → security → service → repository → database | Spring Boot Test + MockMvc against H2 |
| **Security** | Authentication, authorization, customer isolation, input validation | MockMvc with different roles and tokens |
| **Failure handling** | Behaviour when persistence fails | A mocked repository raising a database failure |
| **System / end-to-end** | The running application over HTTP | A verification script against both live servers |

---

## Running the tests

```bash
cd backend
mvn clean package                      # compile, run all tests, build the JAR
mvn test                               # tests only
mvn test -Dtest=FraudRiskServiceTest   # one class
```

```bash
cd frontend
npm run build                          # production build
```

Tests use an isolated in-memory database, so they never touch development data.

---

## Results

**81 tests, 0 failures, 0 errors, 0 skipped.**

| Test class | Tests | Level | Focus |
|---|---|---|---|
| `AuthenticationApiTest` | 11 | Integration + security | Login, registration, role enforcement, token handling |
| `AccountApiTest` | 4 | Integration + security | Accounts, balance, cross-customer isolation |
| `BeneficiaryApiTest` | 5 | Integration | Add, duplicate rejection, own-account rejection, removal |
| `FraudRiskServiceTest` | 10 | **Unit** | Each rule in isolation, score bands, determinism |
| `TransactionWorkflowApiTest` | 20 | Integration | Validation, transfers, risk routing, verification, duplicates, history |
| `FraudReviewApiTest` | 7 | Integration + security | Case review, assignment, decisions, double-decision guard |
| `DisputeApiTest` | 6 | Integration + security | Complaint lifecycle and ownership |
| `AdministrationApiTest` | 8 | Integration + security | Role management, self-lockout guards, reports |
| `AuditApiTest` | 5 | Integration | Audit content and row shape |
| `AuditCommitApiTest` | 1 | Integration, **non-transactional** | Audit durability regression |
| `ResilienceApiTest` | 4 | Failure handling | Database outage, unknown address, health endpoint |

### Concurrency

`ConcurrentTransferApiTest` proves the `@Version` optimistic lock on `account`: two genuinely
simultaneous transfers against the same account are released together with a `CyclicBarrier` (never
`Thread.sleep`), seeded so both cannot succeed. Exactly one settles; the other is rejected with
`409 CONCURRENT_UPDATE`; the final balance reflects exactly one settled transfer.

It commits a real balance change, so it is excluded from the default suite above by a JUnit tag
(`backend/pom.xml`) and run on its own:

```bash
mvn test -Dgroups=concurrency -Dconcurrency.excludedGroups= -Dtest=ConcurrentTransferApiTest
```

Run 16 times during development, every run raced genuinely — a `StaleObjectStateException` on the
losing request — rather than silently serialising, which is the specific failure mode a test like
this can pass without ever exercising. This proves correctness under a single point of contention,
not behaviour under sustained load — see the [Limitations](../README.md#limitations) in the README.

### End-to-end

`scripts/verify_runtime.py` exercises the running API over real HTTP:

```bash
# Terminal 1
cd backend && java -jar target/obfds-backend-1.0.0.jar

# Terminal 2
python scripts/verify_runtime.py
```

**44 / 44 checks passed.** A recorded run is in
[`runtime-verification-output.txt`](runtime-verification-output.txt).

It asserts exact balances from the seeded dataset, so it needs a freshly started backend.

---

## One test class is deliberately different

`AuditCommitApiTest` does **not** run inside a test-managed transaction, unlike every other
integration test.

It exists to prove audit entries are genuinely committed when the surrounding operation is
rejected. Inside a test transaction the row would be visible to the assertions whether or not it
had been committed independently — so the test would pass even if the behaviour were broken.
Written without one, it can only pass if the entry really was committed in its own transaction.

---

## Two defects found by running the system

Both passed the existing test suite. Both were found by running the whole application. Both now
have regression tests written specifically so they *could* fail again.

### The velocity rule counted a transfer against itself

**Symptom** — a transfer expected to score 60 scored 80.

**Cause** — the transaction row was persisted *before* the fraud assessment ran, so the transfer
being assessed was included in the customer's own recent-activity count. Every third transfer
inside the window gained 20 points it should not have had.

**Why the tests missed it** — the rule was unit-tested with a mocked repository, so the count was
whatever the mock returned; the integration tests each made at most two transfers, so the threshold
of three was never crossed.

**Fix** — risk is now assessed before the transaction is stored. Two regression tests: three rapid
transfers must still score 0 and stay LOW; a fourth must score 20 and cite the rule.

### Audit entries on rejected paths were lost

**Symptom** — `LOGIN_FAILURE` and `VERIFICATION_FAILED` were absent from the live audit trail.

**Cause** — `AuditService.failure()` called `record()` directly inside the same class. A
self-invocation does not pass through the Spring proxy, so the `REQUIRES_NEW` propagation never
took effect and the audit write joined the caller's transaction — rolling back with the rejected
operation.

**Why the tests missed it** — the integration tests run inside a test-managed transaction, where
the row is visible to assertions whether or not it was committed independently.

**Fix** — the propagation annotation moved onto the public entry points. Regression test:
`AuditCommitApiTest`, deliberately non-transactional.

A third, smaller issue surfaced at the same time: `/actuator/health` returned 500 because the
actuator dependency was missing while the configuration referenced it, and because the catch-all
exception handler converted a genuine 404 into a 500. Both fixed.

**The lesson recorded in this project:** a test that cannot observe the failure mode is not
evidence.

---

## Security testing

| Scenario | Expected | Result |
|---|---|---|
| Unauthenticated request to a protected endpoint | `401 UNAUTHENTICATED` | Pass |
| Forged or malformed bearer token | `401` | Pass |
| Customer calls an administrator endpoint | `403 FORBIDDEN` | Pass |
| Fraud analyst calls a customer banking endpoint | `403` | Pass |
| Customer reads another customer's balance | `404` — does not confirm existence | Pass |
| Customer reads another customer's transaction | `404` | Pass |
| Customer disputes another customer's transaction | `404` | Pass |
| Customer opens the operations queue / audit / reports | `403` | Pass |
| Invalid input (amount, account number, email, lengths) | `400` with per-field messages | Pass |
| Wrong password vs unknown username | Identical generic message | Pass |
| Simulated database outage | `503`, no internal detail in the body | Pass |

No offensive-security testing was performed. This repository contains no exploit code.

---

## Performance

Load testing has not been performed and no throughput figures are claimed. Observed on an ordinary
laptop with the seeded dataset:

| Operation | Observation |
|---|---|
| Application start-up | ~4–5 seconds |
| Demo data seeding | Under 0.5 seconds |
| Typical API calls | No noticeable delay; the 44-check script completes in seconds |
| Full test suite | ~20 seconds for 81 tests including ten Spring context startups |

Design measures relevant under real load: indexes on the columns the hot queries filter on,
pagination on transaction history and the audit trail, and stateless authentication so more
instances can be added.
