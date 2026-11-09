# Security

What this project actually implements, and what it deliberately does not claim.

> No claim is made that this system is "bank-grade", "production secure" or independently assessed.
> It is a simulation. The measures below are real, but they have not been subjected to a security
> audit or penetration test.

---

## 1. Credentials

**Passwords** are stored only as BCrypt hashes. BCrypt is deliberately slow and salted, so
identical passwords produce different hashes and brute-forcing is expensive. No password is ever
stored, logged or returned in readable form.

**Verification codes** are hashed too. A six-digit code that releases a financial transfer is a
short-lived secret granting a real action, so it is treated exactly like a password. Reading the
database does not reveal it. This is why the demo reads the code from the notification rather than
from storage.

**The JWT signing secret** comes from configuration (or, for a loopback-only local installation, from a
random per-installation file that is created on first start and kept outside the repository). It must be at
least 32 characters and is checked before anything else starts. The repository does contain two *published*
secrets (a development default and a test value), because a development run needs one: the start-up guard
refuses both on every profile except a loopback-only `dev`/`test` run, so a token forged with a published
secret cannot work against a deployment that anyone else can reach (this was the project's most serious
finding: a forged administrator token worked against the default configuration). Rotating the secret signs
everybody out, on purpose. `.env.example` holds placeholders and `.env` is git-ignored.

**Demo passwords** are published for the local demonstration. The seeder has three modes (`none`,
`bootstrap-env`, `demo-fixed`) and the guard allows `demo-fixed` only on an isolated run. A public deployment
seeds the same fictional data with five environment-supplied passwords (12+ characters, none a published demo
password) or seeds no users at all.

---

## 1a. The start-up guard

`StartupSafetyGuard` runs before any other bean exists: before the database connects, before a user is
written and before a port is opened. A run is **isolated** only if it is not the `public` profile, is bound to
loopback and talks to a database on this machine. Anything it cannot classify (an address it cannot parse, a
URL with several hosts) counts as *not* isolated. The service refuses to start, with a message naming each
reason, when:

| Condition | Why |
|---|---|
| no JWT secret, a published secret, or a short or repetitive one | forged-token takeover |
| a generated file secret outside an isolated run | secrets for hosted deployments come from a secret store |
| `demo-fixed` seeding or `open` registration outside an isolated run | published passwords; funded accounts for anyone |
| a CORS origin that is `*`, `localhost`, or not `https` outside an isolated run | cross-origin exposure |
| actuator exposure other than `health` outside an isolated run | information leakage |
| a database on another host without `sslMode=VERIFY_CA/VERIFY_IDENTITY` | unverified TLS |
| web threads × 2 > database pool | pool starvation (below) |

The rules are a pure function with a truth-table test (`StartupSafetyRulesTest`), and the refusals are also
proven by starting the real application (`StartupSafetyGuardTest`).

### Pool starvation

Each request can hold two database connections at once: its business transaction and an audit write that runs
in its own transaction (so a failure is still recorded when the business transaction rolls back). If more
requests than half the pool run together, every connection can be held by a request waiting for a second
one; nothing finishes until the pool timeout and every waiting request fails with HTTP 500. This was observed
on MySQL (pool 8, eight simultaneous transfers: all HTTP 500 after about 22 seconds). The containment is
configuration, not a change to audit semantics: Tomcat is limited to at most half the pool (and the guard
enforces it). A burst of pool+4 simultaneous transfers, bad logins and health checks now produces zero 5xx.

---

## 2. Authentication

Stateless JWT. On login the password is checked against its hash and a token is issued carrying the
username, granted authorities and an expiry. Every later request presents the token in the
`Authorization` header — never a URL parameter, so tokens do not appear in server logs or browser
history.

The filter verifies signature and expiry on every request, then **reads the user from the database**: an
unknown subject, or a disabled account, leaves the request unauthenticated, and the authorities are the
database's current roles, not the token's `roles` claim (which is ignored). So disabling a user or removing a
role takes effect on the very next request, and a token cannot grant more than the account holds today. An
invalid or expired token is treated as an expected condition: the request stays unauthenticated and the entry
point returns `401` with a JSON body rather than an HTML login page.

**Enumeration resistance:** a wrong password, an unknown username and a disabled account all produce the
identical `401` "Invalid username or password."

**Abuse controls:** the login endpoint checks a throttle *before* the password hash. Repeated failures lock
one (username, client address) pair for 30 seconds, doubling up to eight times that, with `429` and
`Retry-After`; while locked even the correct password is refused. The lock is keyed on the pair, not on the
username alone, because a username-only lock would let anyone on the internet lock a customer out by guessing
their name. A separate per-address ceiling limits a client that cycles through usernames. State is in memory
and bounded (least-recently-used eviction), so it cannot be used to exhaust memory; a restart clears it. Behind
a proxy the client address is only meaningful when the proxy's forwarding header is trusted
(`server.forward-headers-strategy`, set for the `public` profile); this has **not** been verified against a
real provider. Input lengths are limited before authentication, so an oversized value never reaches the hash or
the audit log.

**Registration** hands out a funded account, so on a public deployment it is `closed` (or `invite`, with a
shared code compared in constant time and an optional cap on the number of customers).

---

## 3. Authorization

Role-based, declared per endpoint group, with `anyRequest().authenticated()` as the final rule — so
a newly added endpoint is protected by default rather than accidentally public.

Five roles: `CUSTOMER`, `BANK_ADMIN`, `FRAUD_ANALYST`, `OPS_OFFICER`, `SYSTEM_ADMIN`.

The System Administrator is deliberately **not** given banking powers. Operating the servers and
operating the bank are different jobs, and the role model reflects that.

### Ownership checks

Role alone is not sufficient — every customer holds `CUSTOMER`. Every service resolves the caller
from the security context and scopes the query to that customer. **No identifier supplied by the
client is ever trusted as an authorisation input.**

A request for another customer's resource returns **404, not 403**, so the response does not
confirm that the resource exists.

### Administrative guard rails

An administrator cannot remove their own administrator role, and cannot disable their own login.
Without those guards a single mistake could leave the system with nobody able to administer it.

---

## 4. Input validation

Bean Validation on every request body, enforced server-side and independent of anything the browser
does: amount ranges and decimal places, account-number format, email format, password length, text
lengths.

Business rules are checked separately in the service layer: account ownership, account status,
self-transfer, sufficient available balance, duplicate submission, valid state transitions.

Persistence goes through Spring Data JPA with parameterised queries; no SQL is concatenated from
user input.

---

## 5. Error handling

One central handler produces a single error shape for the whole API, on one rule: **anything the
caller can act on gets a specific code and message; anything caused by an internal fault gets a
generic message, and the real detail goes to the server log only.**

`server.error.include-stacktrace` and `include-message` are both set to `never`, so no stack trace
or framework detail reaches a browser.

---

## 6. Data integrity

Integrity is a security property here, because the asset being protected is a balance.

| Control | Purpose |
|---|---|
| Single-transaction settlement | Debit, credit and status change commit together or not at all |
| **Lock-first serialisation** (`RowLocker`) | The source account (and an in-bank destination) is write-locked **before** any balance or held amount is read, so two concurrent requests cannot both see "100,000 available" and both promise 60,000. Optimistic version checks alone cannot stop this: the second request commits a *different* row (its own new transfer) and so never conflicts |
| One global lock order | A case first (if the operation has one), then accounts by ascending id. Every path that locks several rows follows it, so two requests never wait on each other. `READ_COMMITTED` is explicit on these entry points so a request that waited reads what the winner committed |
| Optimistic locking on `account` | Kept as a second line of defence; a lost update is rejected, not silently overwritten |
| Owner- and payload-bound idempotency | A key is honoured only for its owner and the same (source, destination, amount); it is checked again after the lock is taken, so two identical requests in flight together give one transfer. A different customer's key is `409` with nothing disclosed |
| 60-second duplicate window | Only for requests with *no* key |
| Available-balance reservation | Pending transfers cannot be double-spent; a hold whose code expired stops reserving at once |
| Case outcome derived from the transfer | A case is `RESOLVED_APPROVED` only if its transfer is `APPROVED` |
| Final transaction states | `APPROVED`, `BLOCKED`, `FAILED` cannot be changed afterwards |
| `DECIMAL` money, never floating point | No accumulated rounding error |

What was proved, not just argued: on the original code, two simultaneous 60,000 holds against a 100,000
balance were both accepted, eight simultaneous legitimate transfers from one account gave seven `409`s, a
case could be `RESOLVED_APPROVED` over a `FAILED` transfer, and another customer's idempotency key returned
that customer's transfer. Each has a test that failed on that code for exactly that reason and passes now,
on H2 and on MySQL (see [testing](testing.md)).

### Schema changes

The running service holds a database account that can only `SELECT/INSERT/UPDATE/DELETE`. The schema is
created and changed only by a separate migrator (Flyway) with a DDL account; Hibernate merely validates that
the tables match, and a startup check refuses a database that is behind the build. The migrator has no
`clean`, refuses to run the baseline over existing tables, and needs an explicit, confirmed `baseline` to adopt
an existing database. See [database design](database-design.md).

---

## 7. Auditability

Every login (successful and failed), transfer, risk decision, verification attempt, fraud decision,
complaint outcome and administrative change is written to an append-only `audit_log` table.

Audit writes use a **separate transaction**, so an entry survives even when the operation it
records is being rejected — a failed login must leave evidence. Getting this wrong was a real
defect in this project: a self-invocation bypassed the Spring proxy and the propagation setting
silently had no effect. It is now covered by a regression test that deliberately runs outside a
test transaction.

Action names come from a fixed vocabulary, so the same event cannot be recorded under three
spellings and the trail stays filterable.

---

## 8. Transport and browser

- CORS is restricted to a configured origin and **never** uses a wildcard.
- TLS termination is a deployment concern; the proposed environment terminates HTTPS at a reverse
  proxy — see [deployment](deployment.md).
- The frontend hides navigation by role, but that is a usability measure, not a security control.
  Every endpoint is independently protected on the server.
- `SecurityHeadersFilter` adds `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
  `Referrer-Policy: no-referrer`, a strict `Content-Security-Policy: default-src 'none'` (the API
  returns only JSON, never HTML, so nothing needs to be allow-listed), and
  `Strict-Transport-Security` — a standalone filter, added and verified without touching
  authentication, authorization or rate limiting.

---

## 9. Known gaps

Stated plainly rather than omitted:

| Gap | Consequence |
|---|---|
| Token lifetime is 2 hours and there is no "sign out everywhere" button | Disabling a user or removing a role takes effect immediately (the user is re-read on every request), but rotating the signing secret is the only way to invalidate every token at once |
| The login throttle is in memory | A restart clears it; one instance only. Behind a proxy it depends on the forwarding header being trusted, which has not been verified on a real host |
| No multi-factor authentication at login | The verification challenge applies to risky transfers, not to sign-in |
| Verification codes are delivered in-app | Not a genuine out-of-band channel; a real system would use SMS or email |
| A customer statement is not identity verification | It is recorded as evidence and never releases money; an analyst still has to decide |
| Velocity is per customer, not serialised across a customer's accounts | Simultaneous transfers from *different* accounts of one customer can evade `RAPID_TRANSFERS`; each account's money stays exact |
| A mail draft is not delivery | The system sends nothing; "I sent it" is self-reported |
| Provider behaviour is unverified | TLS to a hosted database, proxy IP handling, cold starts and idle behaviour have not been tested on a real provider |
| No dependency scanning in CI | Dependency updates are manual |
| No penetration testing or security audit | The measures above are implemented and tested, not independently assessed |

Addressing these is part of the [full-stack deployment roadmap](full-stack-deployment-roadmap.md).
