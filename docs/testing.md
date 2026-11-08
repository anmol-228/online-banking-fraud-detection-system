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
mvn clean verify                       # compile, run ALL tests (two JVMs, see below), build the JAR
mvn test -Dtest=MoneyPathIntegrityTest -Dsurefire.failIfNoSpecifiedTests=false   # one class
python ../scripts/assert_test_counts.py target/surefire-reports                  # prove the important tests RAN
```

```bash
cd frontend
npm run test:policy                    # retry-safe keys and auth-error handling
npm run test:parity                    # the browser demo's rule engine vs the backend-generated matrix
npm run build                          # production build (also: VITE_APP_MODE=showcase)
```

`mvn test` runs **two Surefire executions in separate JVMs**: `default-test` (everything except tests tagged
`concurrency`) and `committed-data-tests` (only those). The tagged tests commit real rows and run genuinely
overlapping transactions, so they create their own throw-away customers and must not share a JVM with tests
that assume the seeded balances are untouched. A green exit code only means nothing *failed*, so CI also runs
`scripts/assert_test_counts.py`, which reads the Surefire reports and fails unless each important class ran
its minimum number of tests, none were skipped, and the overall total is above a floor. (It undercounts a few
nested-class reports, so its total is a conservative floor, not the headline figure.)

**The same suite runs against a real MySQL.** Set `SPRING_DATASOURCE_URL` (and the username, password, driver
class and `SPRING_JPA_DATABASE_PLATFORM`) to a MySQL 8 and run `mvn test`: the tests then use that database
instead of H2, at the server's default `REPEATABLE READ`, and the 12 MySQL-only migration tests run (against a
separate scratch database, which they empty at the start of each test and never confuse with the test
database). CI has a job for this; locally see [operations](operations.md). The golden demo database is never a
test target.

Tests use an isolated database (in-memory H2 by default), so they never touch development data.

---

## Results

**Backend: 208 tests** (163 in the default execution + 45 in the committed-data execution).

| Database | Result |
|---|---|
| H2 | 0 failures, 0 errors; **12 skipped** (the MySQL-only migration tests, which need MySQL) |
| MySQL 8.4.13, default `REPEATABLE READ` | **0 failures, 0 errors, 0 skipped** (the 12 migration tests run) |

The original 84 tests are unchanged in intent (one was moved off the shared test transaction, below, and the
concurrency test's expectation changed because the behaviour improved). Frontend: 36 policy checks and 168
rule-parity checks (`npm run test:policy`, `npm run test:parity`), plus both builds. There is no component-level
frontend test suite; the screens are verified by building them in both modes and by walkthrough — see
[What is deliberately not covered](#what-is-deliberately-not-covered) below.

**Red first.** Every new test that guards a fix was run against the original code (commit `fea4ea9`, rebuilt
from a verified snapshot) and failed for the stated reason before the fix was written. The fraud-rule
conformance suite and the migration tests are new components, so for them the red proof is a mutation: change
one documented weight and the suite fails; revert and it passes. Those logs are kept with the review package.

| Test class | Tests | Level | Focus |
|---|---|---|---|
| `AuthenticationApiTest` | 11 | Integration + security | Login, registration, role enforcement, token handling |
| `AccountApiTest` | 4 | Integration + security | Accounts, balance, cross-customer isolation |
| `BeneficiaryApiTest` | 5 | Integration | Add, duplicate rejection, own-account rejection, removal |
| `FraudRiskServiceTest` | 10 | **Unit** | Each rule in isolation, score bands, determinism |
| `TransactionWorkflowApiTest` | 20 | Integration | Validation, transfers, risk routing, verification, duplicates, history |
| `FraudReviewApiTest` | 9 | Integration + security | Case review, assignment, decisions, double-decision guard, closing a case opened by failed verification |
| `DisputeApiTest` | 6 | Integration + security | Complaint lifecycle and ownership |
| `AdministrationApiTest` | 8 | Integration + security | Role management, self-lockout guards, reports |
| `AuditApiTest` | 5 | Integration, **non-transactional** | Audit content and row shape. Opts out of the shared test transaction because MySQL's `REPEATABLE READ` pins a snapshot at the test's first read and would hide audit rows committed afterwards by the separate audit transaction; it uses a throw-away customer per test |
| `AuditCommitApiTest` | 1 | Integration, **non-transactional** | Audit durability regression |
| `ResilienceApiTest` | 4 | Failure handling | Database outage, unknown address, health endpoint |
| `AuthorizationFreshnessApiTest` | 6 | Security | A token's role claim is ignored; unknown/disabled subjects and removed roles take effect at once; a disabled login is indistinguishable from a wrong password |
| `LoginHardeningApiTest` | 6 | Security | Throttle and lockout, `Retry-After`, per-username scope, success resets, oversized input rejected before hashing |
| `RegistrationGateApiTest` | 4 | Security | Closed and invite-only registration |
| `SeedModeApiTest` | 3 | Security | The published passwords fail in `bootstrap-env`; `none` seeds no users |
| `StartupSafetyGuardTest` | 11 | Security, starts the real app | Each unsafe configuration refuses to start; positive controls start |
| `StartupSafetyRulesTest` | 16 | Unit | The guard's rules as a truth table (bind address, database host, secrets, seed, registration, CORS, TLS, pool ratio) |
| `AuthThrottleTest` | 7 | Unit | Backoff, expiry, per-pair scope, normalisation, memory bound, with a controllable clock |
| `LocalJwtSecretStoreTest` | 6 | Unit | Per-install secret: created once, reused after a restart, rotation invalidates tokens, a default-secret token is rejected |
| `FraudRuleSpecificationTest` | 5 | Unit | All 144 categorical combinations and 24 boundary cases computed from the documented table; worked examples; the generated matrix |
| `SchemaMigrationMysqlTest` | 12 | MySQL only | Migrations: empty and populated databases, negative controls, ENUM parity, Hibernate `validate`, version guard |
| `NoOutboundDeliveryTest` | 3 | Architecture | The application contains no mail, HTTP-client or socket code and no "delivered" state |

### Concurrency

The committed-data execution (tag `concurrency`, 45 tests) is where races are tested. Threads are released
together by a barrier (never `Thread.sleep`), and for the money path the overlap is **forced**: a bounded
barrier inside the risk evaluation holds each request there until all have arrived, which on unserialised
code guarantees they have all read the same "available balance". On correct code only one request can be
inside at a time, so the wait times out and the request carries on; a test must not hang on correct code.

| Class | Tests | What it proves |
|---|---|---|
| `ConcurrentTransferApiTest` | 1 | Two simultaneous transfers against one account: exactly one settles, the loser waits for the winner's lock, then sees the reduced balance and is refused for insufficient funds (`422`). (It used to be a `409` from an optimistic-version conflict; a `409`/`503` here would now mean the requests are no longer serialised.) |
| `MoneyPathIntegrityTest` | 5 | Two MEDIUM holds, or a MEDIUM and a HIGH, that together exceed the balance: only one is accepted and `balance − held ≥ 0` on the database; eight simultaneous legitimate transfers all settle and the money is conserved to the paisa; a case is never `RESOLVED_APPROVED` over a `FAILED` transfer |
| `IdempotencyApiTest` | 6 | Another customer's key: `409`, nothing disclosed; same customer different payload: `409`; exact retry and `1500` vs `1500.00`: replayed once; two identical requests in flight together: one row, one debit, both `201`; a new key is a new transfer |
| `PoolStarvationApiTest` | 1 | Real HTTP: pool+4 simultaneous transfers, bad logins and health checks give **zero** 5xx and every transfer settles |
| `HoldExpiryApiTest` | 5 | Expired hold stops reserving; late code ends `FAILED` and closes the alert; a stale hold is swept on the account's next use; the customer's view agrees; EXP-5 characterises the documented limitation: an untouched expired hold frees funds and reads `FAILED` to the customer, while its stored alert and the analyst alert list still read open |
| `CustomerCancelApiTest` | 10 | Cancel releases funds and closes the case without accusation; approval after cancel refused; limited to the owner's open holds; **cancel vs analyst approval raced 5 times**: exactly one winner, and `(approved ⇔ balance reduced ⇔ case approved)` every time |
| `CustomerResponseApiTest` | 6 | The statement is evidence only: write-once, "not mine" outranks and blocks approval, no sequence of statements moves money |
| `EvidenceAndEscalationApiTest` | 11 | Evidence export is minimal, hashed and verifiable, and carries no free text, and exporting or drafting never changes the hash (EVI-5; PRIV-1: analyst remarks never reach the customer, in the transfer view or the notifications, while staff keep them; EVI-2 puts a name, address, phone and e-mail into the analyst's remarks and asserts none of it appears); the handoff is allow-listed, injection-proof, rate-limited and never automatic |

Measured on MySQL 8.4.13: the committed-data execution passed 13/13 in each of five fresh JVMs (the suite had 13
tests at that point, before the workflow classes were added), and a fixed-code run recorded **0** InnoDB
deadlocks and 11 row-lock waits (the serialisation visibly working). The server's deadlock counter read 7 after an
earlier sequence of runs that included the original code; that reading is not attributed to any single run. This
is correctness under contention and a burst larger than the pool; it is not a sustained load test
— see the [Limitations](../README.md#limitations) in the README.

### End-to-end

`scripts/verify_runtime.py` exercises the running API over real HTTP:

```bash
# Terminal 1
cd backend && java -jar target/obfds-backend-1.0.0.jar

# Terminal 2
python scripts/verify_runtime.py
```

**45 / 45 checks passed.** A recorded run is in
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

## Three defects found by running the system

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

### Failed verification attempts were never counted

**Symptom** — against the running application, a wrong verification code was rejected with the
message "You have 2 attempt(s) remaining", but re-reading the transfer showed `attempts: 0` and
`attemptsRemaining: 3`. Eight consecutive wrong codes left the transfer still waiting for
verification. The documented three-attempt limit never fired at all.

**Cause** — `submitVerification()` increments the attempt counter and *then* reports the wrong
code by throwing `BusinessRuleException`. Under Spring's default rollback-on-runtime-exception
rule the whole transaction rolled back, discarding the increment along with it. The same rollback
discarded the two states that depend on it: the `FAILED` verification status — which is the row
the `REPEATED_FAILED_VERIFICATION` scoring rule counts, so that rule could never fire either — and
the block applied once the attempts ran out.

**Why the tests missed it** — exactly the mechanism recorded one section above, in a different
code path. The API tests extend a base class annotated `@Transactional`, so each test method
shares one persistence context; a counter incremented on a managed entity is visible to a later
read in the same method whether or not it was ever committed. Two tests asserted the attempt
counter and the three-attempt block, and both passed against a build where neither worked.

**Fix** — `@Transactional(noRollbackFor = BusinessRuleException.class)` on the method, so the
recorded attempt survives the rejection it reports. Regression test:
`VerificationAttemptPersistenceApiTest`, deliberately non-transactional, which fails on the
unfixed code at the first re-read.

A third, smaller issue surfaced at the same time: `/actuator/health` returned 500 because the
actuator dependency was missing while the configuration referenced it, and because the catch-all
exception handler converted a genuine 404 into a 500. Both fixed.

**The lesson recorded in this project:** a test that cannot observe the failure mode is not
evidence — and the second time this project learned it, the first lesson had already been written
down. Recognising the pattern is not the same as having removed it; the durability of every write
that happens on a rejected path has to be checked outside a test-managed transaction, one path at
a time.

---

## What is deliberately not covered

Stating the gaps is part of the evidence; a coverage claim that hides its own boundary is not one.

| Gap | Status |
|---|---|
| **Automated frontend tests** | None exist. Every figure on this page is backend. The React application is verified by building it in both modes and by manual walkthrough against the screenshots |
| **Durability of writes on rejected paths** | The API tests share one test-managed transaction, so they cannot tell a committed write from an uncommitted one. Two dedicated non-transactional tests — `AuditCommitApiTest` and `VerificationAttemptPersistenceApiTest` — cover the two paths where this mattered; the general property is not proven for paths beyond those |
| **Load and performance testing** | Not performed. No throughput or latency figure is claimed anywhere |
| **Sustained concurrency** | `ConcurrentTransferApiTest` proves the optimistic lock under a single point of contention, not behaviour under sustained load |
| **Penetration testing** | Not performed, and out of scope. This repository contains no exploit code |

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
| Typical API calls | No noticeable delay; the 45-check script completes in seconds |
| Full test suite | a few minutes for all 208 tests on H2 (two JVMs, several application contexts, a real-HTTP burst test and forced race tests); the 12 migration tests add a few seconds on MySQL |

Design measures relevant under real load: indexes on the columns the hot queries filter on,
pagination on transaction history and the audit trail, and stateless authentication so more
instances can be added.
