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

**The JWT signing secret** comes from configuration, must be at least 32 characters, and is
validated at start-up — the application refuses to start with a short secret rather than running
with weak signing. No secret is committed; `.env.example` holds placeholders and `.env` is
git-ignored.

---

## 2. Authentication

Stateless JWT. On login the password is checked against its hash and a token is issued carrying the
username, granted authorities and an expiry. Every later request presents the token in the
`Authorization` header — never a URL parameter, so tokens do not appear in server logs or browser
history.

The filter verifies signature and expiry on every request. An invalid or expired token is treated
as an expected condition: the request stays unauthenticated and the entry point returns `401` with
a JSON body rather than an HTML login page.

**Enumeration resistance:** a wrong password and an unknown username produce the identical message
"Invalid username or password."

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
| Optimistic locking on `account` | Concurrent updates are rejected, not silently overwritten |
| Idempotency key + 60-second duplicate window | A double submission cannot create two transfers |
| Available-balance reservation | Pending transfers cannot be double-spent |
| Final transaction states | `APPROVED`, `BLOCKED`, `FAILED` cannot be changed afterwards |
| `DECIMAL` money, never floating point | No accumulated rounding error |

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

---

## 9. Known gaps

Stated plainly rather than omitted:

| Gap | Consequence |
|---|---|
| **Tokens cannot be revoked before expiry** | Disabling a login prevents the next sign-in but does not invalidate a token already issued (default lifetime 2 hours) |
| No rate limiting or account lockout | Repeated login attempts are audited but not throttled |
| No multi-factor authentication at login | The verification challenge applies to risky transfers, not to sign-in |
| Verification codes are delivered in-app | Not a genuine out-of-band channel; a real system would use SMS or email |
| No security headers middleware | CSP, HSTS and similar would be added at the reverse proxy |
| No dependency scanning in CI | Dependency updates are manual |
| No penetration testing or security audit | The measures above are implemented, not independently verified |
| Schema managed by `ddl-auto` | Not versioned or reviewable as migrations |

Addressing these is part of the [full-stack deployment roadmap](full-stack-deployment-roadmap.md).
