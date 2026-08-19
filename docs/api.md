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
`CONCURRENT_UPDATE` · `DATA_INTEGRITY_VIOLATION` · `SERVICE_UNAVAILABLE` · `INTERNAL_ERROR`

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

A wrong password and an unknown username both return the same generic message, so the response does
not reveal which usernames exist.

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
  "completedAt": null
}
```

`idempotencyKey` is optional but recommended: sending the same key twice returns the original
transaction instead of creating a second one. Without a key, an identical transfer within 60
seconds is rejected with `409 DUPLICATE_TRANSACTION`.

### Other transaction endpoints

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/transactions?page=0&size=20` | Paginated history |
| `GET` | `/api/transactions/{reference}` | Details and status |
| `GET` | `/api/transactions/{reference}/verification` | Outstanding challenge state |
| `POST` | `/api/transactions/{reference}/verify` | Submit the six-digit code |

```json
{ "code": "449657" }
```

A wrong code returns `422 INVALID_VERIFICATION_CODE` with the number of attempts remaining. Three
failures block the transfer and escalate the alert to a fraud case.

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

```json
{
  "decision": "BLOCK",
  "remarks": "Customer did not recognise this payee."
}
```

`remarks` is mandatory — the decision is written to the audit trail, and a decision without a
reason cannot be reviewed later. A case cannot be decided twice.

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
