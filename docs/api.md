# API Reference

Base URL in local development: `http://localhost:8080`

All endpoints except `/api/auth/register`, `/api/auth/login` and `/actuator/health` require:

```http
Authorization: Bearer <token>
```

---

## Status codes

| Code | Meaning |
|---|---|
| `200` | Success |
| `201` | Created |
| `400` | Validation failure or malformed body |
| `401` | Not authenticated, or invalid credentials |
| `403` | Authenticated but the role is not permitted |
| `404` | Not found — also returned when a resource exists but belongs to someone else |
| `409` | Conflict: duplicate transfer, concurrent update, unique constraint |
| `410` | Verification code expired |
| `422` | Business rule violation |
| `503` | Persistence failure |

## Error shape

Every failure returns the same body, so a client never has to interpret an unknown structure:

```json
{
  "timestamp": "2026-08-19T03:02:34Z",
  "status": 422,
  "code": "INSUFFICIENT_BALANCE",
  "message": "The available balance in this account is not sufficient for this transfer.",
  "path": "/api/transactions/transfer",
  "fieldErrors": {}
}
```

`fieldErrors` is populated only for validation failures:

```json
{
  "status": 400,
  "code": "VALIDATION_FAILED",
  "message": "Some of the information you entered is not valid.",
  "fieldErrors": { "amount": "Amount must be at least 1.00" }
}
```

### Error codes

`INVALID_CREDENTIALS` · `UNAUTHENTICATED` · `FORBIDDEN` · `ACCOUNT_DISABLED` · `NOT_FOUND` ·
`VALIDATION_FAILED` · `MALFORMED_REQUEST` · `USERNAME_TAKEN` · `EMAIL_TAKEN` ·
`BENEFICIARY_EXISTS` · `OWN_ACCOUNT_AS_BENEFICIARY` · `INSUFFICIENT_BALANCE` ·
`SAME_ACCOUNT_TRANSFER` · `ACCOUNT_NOT_ACTIVE` · `DUPLICATE_TRANSACTION` · `INVALID_STATE` ·
`INVALID_VERIFICATION_CODE` · `VERIFICATION_FAILED` · `VERIFICATION_EXPIRED` ·
`NO_PENDING_VERIFICATION` · `CASE_ALREADY_RESOLVED` · `DISPUTE_ALREADY_OPEN` ·
`DISPUTE_ALREADY_CLOSED` · `CANNOT_REMOVE_OWN_ADMIN_ROLE` · `CANNOT_DISABLE_SELF` ·
`CONCURRENT_UPDATE` · `DATA_INTEGRITY_VIOLATION` · `SERVICE_UNAVAILABLE` · `INTERNAL_ERROR` ·
`REGISTRATION_CLOSED` · `TOO_MANY_ATTEMPTS` · `IDEMPOTENCY_KEY_REUSED` · `RESPONSE_ALREADY_RECORDED` ·
`NO_REVIEW_CASE` · `CUSTOMER_REPORTED_NOT_MINE` · `INVALID_RECIPIENT_KEY` · `UNKNOWN_RECIPIENT` ·
`ESCALATION_LIMIT` · `NO_HANDOFF`

`CONCURRENT_UPDATE` (409) now means "another operation held the same account at that moment; nothing
was changed, try again". Money-moving operations are serialised per account, so it is a backstop and not
an expected result.

---

## Authentication

### `POST /api/auth/register`

```json
{
  "username": "new.customer",
  "password": "StrongPass@123",
  "fullName": "Sanjay Patel",
  "email": "sanjay.patel@demomail.example",
  "phone": "9876500099",
  "address": "7 Nehru Street, Surat"
}
```

Creates a login, a banking profile and one opening savings account. Returns `201` with a token.

Registration is governed by `obfds.registration.mode`:

| Mode | Behaviour |
|---|---|
| `open` | anyone may register (only allowed on a loopback-only `dev`/`localdemo` run) |
| `invite` | the request must carry the shared `inviteCode`; a wrong or missing one is refused |
| `closed` | always refused (the default, and the only default for a public deployment) |

A refused request returns `403 REGISTRATION_CLOSED` with the same message in every case, so a wrong code
cannot be told apart from a closed door. An optional `inviteCode` string (max 100) is read only in `invite`
mode.

### `POST /api/auth/login`

```json
{ "username": "ravi.kumar", "password": "Customer@123" }
```

```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "expiresInSeconds": 7200,
  "username": "ravi.kumar",
  "fullName": "Ravi Kumar",
  "roles": ["CUSTOMER"],
  "customerNumber": "CUST10000001"
}
```

A wrong password, an unknown username and a **disabled** account all return the same `401
INVALID_CREDENTIALS`, so the response reveals nothing about which usernames exist or are disabled.

`username` is limited to 60 characters and `password` to 128; longer values are a `400` and never reach the
password hash. Repeated failures from one client for one username lock that pair for a short, doubling time:
`429 TOO_MANY_ATTEMPTS` with a `Retry-After` header (seconds). While locked, even the correct password is
refused. A separate per-address ceiling slows a client that cycles through many usernames.

Every authenticated request re-reads the user from the database: a token for a disabled or deleted user is
`401` immediately, and the roles in force are the database's, not the token's.

### `GET /api/auth/me`

Returns the profile of the caller — used by the frontend to restore a session after a refresh.

---

## Accounts — role `CUSTOMER`

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/accounts` | List own accounts |
| `GET` | `/api/accounts/{id}` | One account |
| `GET` | `/api/accounts/{accountNumber}/balance` | Balance enquiry |

```json
{
  "accountNumber": "900000000001",
  "balance": 147500.00,
  "availableBalance": 92500.00,
  "reservedAmount": 55000.00,
  "currency": "INR",
  "asOf": "2026-08-19T18:05:00Z"
}
```

`availableBalance` excludes amounts reserved by transfers that are still pending.

---

## Beneficiaries — role `CUSTOMER`

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/beneficiaries` | List saved payees |
| `POST` | `/api/beneficiaries` | Add a payee |
| `GET` | `/api/beneficiaries/{id}` | One payee |
| `DELETE` | `/api/beneficiaries/{id}` | Deactivate a payee |

```json
{
  "name": "Nikhil Rao",
  "accountNumber": "400000000077",
  "bankName": "Example Bank",
  "ifscCode": "EXMP0000456",
  "nickname": "Colleague"
}
```

Account number must be 9–18 digits. A customer cannot add their own account, and cannot save the
same account twice. Removal deactivates rather than deletes, so past transactions still resolve.

---

## Transactions — role `CUSTOMER`

### `POST /api/transactions/transfer`

```json
{
  "sourceAccountNumber": "900000000001",
  "destinationAccountNumber": "900000000003",
  "amount": "55000.00",
  "description": "Quarterly rent payment",
  "idempotencyKey": "b7f3e2a1-..."
}
```

Returns `201`. The `status` field tells the caller what happened:

| Status | Meaning |
|---|---|
| `APPROVED` | Low risk — completed, money moved |
| `PENDING_VERIFICATION` | Medium risk — held, a code was issued |
| `PENDING` | High risk — held for fraud review |

```json
{
  "reference": "TXN-20260819-726BBC",
  "sourceAccountNumber": "900000000001",
  "destinationAccountNumber": "900000000003",
  "destinationName": "Meera Nair",
  "amount": 55000.00,
  "currency": "INR",
  "status": "PENDING_VERIFICATION",
  "statusReason": "Additional verification is required before this transfer can proceed.",
  "riskLevel": "MEDIUM",
  "riskScore": 35,
  "riskReason": "Transfer amount is at or above the high limit of 50000 (+35)",
  "verificationRequired": true,
  "initiatedBy": "ravi.kumar",
  "createdAt": "2026-08-19T18:05:00Z",
  "completedAt": null,
  "reviewDueAt": null,
  "customerResponse": null
}
```

`reviewDueAt` and `customerResponse` are set only while a HIGH-risk transfer is held: the *target* time an
analyst is expected to review it (never enforced) and what the customer said about it.

**Idempotency.** `idempotencyKey` is optional but recommended. The key belongs to one customer and one
request (source account, destination account, amount compared as a number, so `1500` equals `1500.00`):

| Situation | Result |
|---|---|
| same customer, same request, again (timeout, retry, two in flight at once) | `201` with the **original** transfer; money moves once |
| same customer, **different** request | `409 IDEMPOTENCY_KEY_REUSED` |
| **another customer's** key | `409 IDEMPOTENCY_KEY_REUSED`, with nothing about the other transfer in the response |
| a **new** key with the same details | a **new, intentional** transfer (a key identifies one attempt, not "similar" payments) |

Without a key, an identical transfer within 60 seconds is rejected with `409 DUPLICATE_TRANSACTION`.

A client should keep the same key until it has a definitive answer, and replace it only after a definitive
rejection (4xx other than `409 CONCURRENT_UPDATE`/`429`); the web front end does exactly this.

### Other transaction endpoints

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/transactions?page=0&size=20` | Paginated history |
| `GET` | `/api/transactions/{reference}` | Details and status |
| `GET` | `/api/transactions/{reference}/verification` | Outstanding challenge state |
| `POST` | `/api/transactions/{reference}/verify` | Submit the six-digit code |
| `POST` | `/api/transactions/{reference}/cancel` | Cancel a transfer that is still held |
| `POST` | `/api/transactions/{reference}/response` | State whether a held HIGH-risk transfer is yours |

```json
{ "code": "449657" }
```

A wrong code returns `422 INVALID_VERIFICATION_CODE` with the number of attempts remaining. Three
failures block the transfer and escalate the alert to a fraud case. A code that **expires** unused ends the
transfer `FAILED` (not `BLOCKED`), frees the funds and closes the alert; entering it afterwards returns
`410 VERIFICATION_EXPIRED`. A hold whose code has expired is also shown as `FAILED` and no longer counts
toward `reservedAmount`.

**`POST …/cancel`** is allowed while the status is `PENDING` or `PENDING_VERIFICATION`. The transfer becomes
`FAILED` with the reason "Cancelled by customer before release.", the funds are released, a related case is
closed with a remark that this is *not a fraud finding*. Anything else is `422 INVALID_STATE`.

**`POST …/response`** body `{ "response": "CONFIRMED_MINE" | "NOT_MINE" }`. Recorded once per held HIGH-risk
transfer: a later `NOT_MINE` may replace `CONFIRMED_MINE`, nothing else may change it
(`409 RESPONSE_ALREADY_RECORDED`). It is **evidence for the analyst only**: it never releases or blocks the
transfer, and a transfer with no review case is `422 NO_REVIEW_CASE`.

---

## Notifications — role `CUSTOMER`

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/notifications` | List messages |
| `GET` | `/api/notifications/unread-count` | Badge count |
| `POST` | `/api/notifications/{id}/read` | Mark one read |
| `POST` | `/api/notifications/read-all` | Mark all read |

Types: `TRANSACTION`, `SECURITY`, `FRAUD_ALERT`, `VERIFICATION`, `DISPUTE`, `GENERAL`.

The verification code is delivered here rather than by SMS — a deliberate substitution so the
project needs no external messaging provider.

---

## Disputes

| Method | Path | Role | Purpose |
|---|---|---|---|
| `POST` | `/api/disputes` | `CUSTOMER` | Raise a complaint |
| `GET` | `/api/disputes` | `CUSTOMER` | Own complaints |
| `GET` | `/api/disputes/queue` | `OPS_OFFICER`, `BANK_ADMIN` | Staff queue |
| `POST` | `/api/disputes/{reference}/resolve` | `OPS_OFFICER`, `BANK_ADMIN` | Record an outcome |

```json
{
  "transactionReference": "TXN-20260819-726BBC",
  "subject": "I did not authorise this transfer",
  "description": "This transfer appeared on my account and I did not make it."
}
```

Only one dispute may be open per transaction at a time.

---

## Fraud monitoring — roles `FRAUD_ANALYST`, `BANK_ADMIN`

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/alerts?status=OPEN` | Alert dashboard, optional status filter |
| `GET` | `/api/alerts/{reference}` | One alert |
| `GET` | `/api/fraud-cases` | Case list |
| `GET` | `/api/fraud-cases/{reference}` | One case |
| `POST` | `/api/fraud-cases/{reference}/assign` | Take ownership |
| `POST` | `/api/fraud-cases/{reference}/decision` | Approve or block |
| `GET` | `/api/fraud-cases/{reference}/evidence` | Download the evidence file (JSON attachment) |
| `GET` | `/api/fraud-cases/escalation-recipients` | The configured team mailboxes (keys; address partly hidden) |
| `POST` | `/api/fraud-cases/{reference}/escalation` | Prepare a mail **draft** to one configured mailbox |
| `POST` | `/api/fraud-cases/{reference}/escalation/attest` | Record that the user sent the draft (self-reported) |

```json
{
  "decision": "BLOCK",
  "remarks": "Customer did not recognise this payee."
}
```

`remarks` is mandatory — the decision is written to the audit trail, and a decision without a
reason cannot be reviewed later. A case cannot be decided twice.

The case response also carries `customerResponse`, `customerRespondedAt` and `reviewDueAt`. Two rules protect
the money:

- **The outcome is derived from what happened to the transfer.** `RESOLVED_APPROVED` is returned only if the
  transfer really became `APPROVED`. If the release could not be funded the transfer ends `FAILED`, the case
  is `RESOLVED_BLOCKED`, and its remarks say the approval was not applied.
- **`APPROVE` after the customer said "not mine"** is refused: `409 CUSTOMER_REPORTED_NOT_MINE`. Blocking
  still works.

### Evidence export

`GET …/evidence` returns `application/json` as a download (`Content-Disposition: attachment`,
`X-Content-Type-Options: nosniff`, `Cache-Control: no-store`). It contains the case, the transfer (account
numbers masked to the last four digits, no name), the customer **number**, each rule that fired with its
points, the verification counts, the customer's labelled statement and a timeline of actions and outcomes. It
leaves out the customer's name, e-mail, telephone and address, the transfer's free-text description, the
idempotency key, code hashes and every audit "details" text. **No free text is exported at all:** the analyst's
remarks and the transfer's status-reason sentence (which quotes those remarks) are omitted. In their place the
file carries `analystRemarksOmitted` and `analystRemarkCharacters` (the length only) in the case section, and a
fixed `outcomeCode` (for example `BLOCKED_BY_ANALYST`) in the transfer section. A reviewer reads the remarks in
the case screen. Keys are sorted and money is a plain decimal string, so the same state
always gives the same bytes (the export's own bookkeeping — exports, mail drafts, "I sent it" — is not listed in the
timeline, so exporting again or preparing a draft does not change the hash), and an `integrity` block holds a SHA-256 over everything else, which is also
recorded in the audit trail (`EVIDENCE_EXPORTED`). Customers get `403`. The file says plainly that it is a
simulation artefact, not a report to anyone.

### Mail-draft handoff

`POST …/escalation` body `{ "recipientKey": "ops" }` selects a mailbox **by key**; the request can never carry
an address. The server validates the key against a strict pattern and the configured list, builds a fixed
subject (`[SIMULATION] OBFDS case <reference>`) and a body containing only the case reference and the evidence
hash, and returns a `mailto:` link:

```json
{ "state": "ESCALATION_HANDOFF_ISSUED", "recipientKey": "ops", "mailto": "mailto:…?subject=…&body=…",
  "evidenceSha256": "…", "notice": "This is a draft, not delivery. …" }
```

**The system sends nothing.** Opening the link is not delivery; the user sends it from their own mail program.
`POST …/escalation/attest` records "the user says they sent it" (state `ESCALATION_USER_ATTESTED_SENT`, labelled
self-reported). A malformed or injected key is `400`, an unconfigured one `422`, and more than the daily limit
(per staff user, default 10) is `429 ESCALATION_LIMIT`. No hold, expiry, block or review target creates a handoff
automatically. Configured mailboxes of authorities, regulators or banks are refused at start-up.

---

## Administration — role `BANK_ADMIN`

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/admin/users` | List users with roles and status |
| `GET` | `/api/admin/roles` | Available role names |
| `PUT` | `/api/admin/users/{id}/roles` | Replace the role set |
| `PUT` | `/api/admin/users/{id}/status` | Enable or disable a login |

```json
{ "roles": ["OPS_OFFICER", "FRAUD_ANALYST"] }
```

The **complete** set is sent, not a single role to add, so the result does not depend on what the
roles happened to be when the screen was loaded.

Two guards cannot be overridden: an administrator cannot remove their own administrator role, and
cannot disable their own login.

Role changes take effect on the user's next sign-in, because roles are carried in the token.

---

## Audit — roles `BANK_ADMIN`, `SYSTEM_ADMIN`

### `GET /api/audit?username=&action=&page=0&size=25`

Paginated, filterable by username and action.

Actions: `REGISTER` · `LOGIN_SUCCESS` · `LOGIN_FAILURE` · `BENEFICIARY_ADDED` ·
`BENEFICIARY_REMOVED` · `TRANSFER_INITIATED` · `TRANSFER_REJECTED` · `RISK_EVALUATED` ·
`FRAUD_ALERT_RAISED` · `VERIFICATION_REQUESTED` · `VERIFICATION_SUCCESS` · `VERIFICATION_FAILED` ·
`TRANSACTION_APPROVED` · `TRANSACTION_BLOCKED` · `FRAUD_CASE_OPENED` · `FRAUD_CASE_DECIDED` ·
`DISPUTE_SUBMITTED` · `DISPUTE_RESOLVED` · `USER_ROLES_UPDATED` · `USER_STATUS_UPDATED` ·
`REPORT_GENERATED`

Rejected attempts appear too — audit entries are written in their own transaction so a failed login
survives the rejection.

---

## Reports — roles `BANK_ADMIN`, `FRAUD_ANALYST`, `OPS_OFFICER`

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/reports/operational` | Volumes, statuses, value approved, open complaints |
| `GET` | `/api/reports/fraud` | Risk mix, alert and case counts, alert rate |

Every figure is a direct count or sum over stored data, so it can be checked against the
transaction list. Generating a report is itself audited.

The fraud report's `detectionRatePercent` is the share of transactions that raised an alert — it
measures how often the rules **fired**, not how accurate they were.

---

## Health

### `GET /actuator/health`

Public and unauthenticated so an external monitor can poll it. Only the `health` endpoint is
exposed; metrics, environment and bean endpoints are not, because they would disclose internal
detail.

```json
{ "status": "UP" }
```
