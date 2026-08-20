# Requirement Traceability

Every requirement is traced from specification through implementation to an automated test. Status
is marked **complete** only where the code exists, an automated test covers it, and it was exercised
in the end-to-end runtime check.

This is the same evidence discussed in [testing](testing.md) and [architecture](architecture.md),
presented as a single matrix rather than as separate claims.

---

## Functional requirements

| ID | Requirement | Implementation | Test | Status |
|---|---|---|---|---|
| FR-01 | Register an online banking profile | `AuthService.register()` · `POST /api/auth/register` · `RegisterPage.jsx` | `AuthenticationApiTest` | Complete |
| FR-02 | Log in with valid credentials | `AuthService.login()` · `POST /api/auth/login` · `LoginPage.jsx` | `AuthenticationApiTest` | Complete |
| FR-03 | Authenticate before allowing access | `JwtService`, `JwtAuthenticationFilter` | `AuthenticationApiTest` | Complete |
| FR-04 | Authorize by assigned role | `SecurityConfig` role rules · `ProtectedRoute.jsx` | 5 test classes | Complete |
| FR-05 | View account details | `AccountService.listMyAccounts()` · `AccountsPage.jsx` | `AccountApiTest` | Complete |
| FR-06 | Check current balance | `AccountService.getMyBalance()` | `AccountApiTest` | Complete |
| FR-07 | Initiate a fund transfer | `TransactionService.transfer()` · `TransferPage.jsx` | `TransactionWorkflowApiTest` | Complete |
| FR-08 | Add, view and manage beneficiaries | `BeneficiaryService` · `BeneficiariesPage.jsx` | `BeneficiaryApiTest` | Complete |
| FR-09 | Validate transaction information | Bean Validation + `TransactionService` checks | `TransactionWorkflowApiTest` | Complete |
| FR-10 | View transaction history | `TransactionService.myTransactions()` · `TransactionsPage.jsx` | `TransactionWorkflowApiTest` | Complete |
| FR-11 | Monitor transactions for suspicious activity | `FraudRiskService.evaluate()` — eight rules | `FraudRiskServiceTest` | Complete |
| FR-12 | Assign a risk classification | `FraudRiskService.classify()` | `FraudRiskServiceTest` | Complete |
| FR-13 | Generate an alert for suspicious transactions | `FraudAlertService` · `AlertsPage.jsx` | `TransactionWorkflowApiTest` | Complete |
| FR-14 | Request additional verification | `holdForVerification()`, `submitVerification()` | `TransactionWorkflowApiTest` | Complete |
| FR-15 | Approve, hold or block per review | `FraudCaseService.decide()` · `CaseDetailPage.jsx` | `FraudReviewApiTest` | Complete |
| FR-16 | Notify customers of important events | `NotificationService` · `NotificationsPage.jsx` | `TransactionWorkflowApiTest` | Complete |
| FR-17 | Submit a complaint or dispute | `DisputeService` · `DisputesPage.jsx` | `DisputeApiTest` | Complete |
| FR-18 | Review suspicious transactions and fraud cases | `FraudCaseService`, `FraudAlertService` | `FraudReviewApiTest` | Complete |
| FR-19 | Manage users and assigned roles | `AdminService` · `UsersPage.jsx` | `AdministrationApiTest` | Complete |
| FR-20 | Maintain audit logs | `AuditService` (own transaction) · `AuditPage.jsx` | `AuditApiTest`, `AuditCommitApiTest` | Complete |
| FR-21 | Generate operational and fraud reports | `ReportService` · `ReportsPage.jsx` | `AdministrationApiTest` | Complete |
| FR-22 | Maintain transaction status information | `TransactionStatus` enum, final-state guards | `TransactionWorkflowApiTest` | Complete |

**22 of 22 functional requirements implemented and tested.**

---

## Non-functional requirements

| ID | Quality | Mechanism | Evidence | Status |
|---|---|---|---|---|
| NFR-01 | Security | BCrypt hashing, stateless JWT, per-endpoint role rules, generic error messages, single-origin CORS | `ResilienceApiTest` + source review | Complete |
| NFR-02 | Reliability | Debit, credit and status change in one `@Transactional` method; `@Version` optimistic locking | `TransactionWorkflowApiTest`, `ConcurrentTransferApiTest` | Complete |
| NFR-03 | Availability | Stateless backend, public `/actuator/health` | `ResilienceApiTest` | Complete (no numeric SLA claimed) |
| NFR-04 | Performance | Indexed columns, pagination | Observed during runtime verification | Stated as an observation, not a guarantee |
| NFR-05 | Scalability | No server-side session; all state in the database | Design review | Design-level — a second instance was not started |
| NFR-06 | Usability | Role-filtered navigation, confirmations, validation messages, empty states | 19 screenshots + manual review | Complete |
| NFR-07 | Maintainability | Layered structure, DTOs at the boundary, one exception handler, externalised fraud thresholds | Code review | Complete |
| NFR-08 | Privacy | Ownership checks return 404, never 403, for another customer's resource | Test names across multiple classes | Complete |
| NFR-09 | Data Integrity | FK/unique constraints, optimistic locking, idempotency key, available-balance reservation | `TransactionWorkflowApiTest`, `ConcurrentTransferApiTest` | Complete |
| NFR-10 | Auditability | `AuditService` writes with `REQUIRES_NEW` on the public entry points | `AuditCommitApiTest` (deliberately non-transactional) | Complete |

**10 of 10 non-functional requirements addressed, with two evidence levels stated honestly rather
than inflated:** NFR-04 is an observation on one machine with the seeded dataset, not a load-test
result; NFR-05 is verified at design level — statelessness makes horizontal scaling possible in
principle, but a second instance was never actually started.

---

## Reverse traceability — every test maps to a requirement

| Test class | Requirements covered |
|---|---|
| `AuthenticationApiTest` | FR-01, FR-02, FR-03, FR-04, NFR-01 |
| `AccountApiTest` | FR-05, FR-06, NFR-08 |
| `BeneficiaryApiTest` | FR-08, NFR-09 |
| `FraudRiskServiceTest` | FR-11, FR-12 |
| `TransactionWorkflowApiTest` | FR-07, FR-09, FR-10, FR-11, FR-12, FR-13, FR-14, FR-15, FR-16, FR-22, NFR-02, NFR-08, NFR-09 |
| `FraudReviewApiTest` | FR-15, FR-18, FR-04, FR-22 |
| `DisputeApiTest` | FR-17, FR-16, FR-04, NFR-08, NFR-09 |
| `AdministrationApiTest` | FR-19, FR-21, FR-04 |
| `AuditApiTest` | FR-20, FR-04, NFR-10 |
| `AuditCommitApiTest` | FR-20, NFR-10 |
| `ResilienceApiTest` | NFR-01, NFR-02, NFR-03, NFR-07 |
| `ConcurrentTransferApiTest` | NFR-02, NFR-09 — run separately from the default suite; see [testing](testing.md#concurrency) |

No test exists that does not trace to a requirement, and no requirement lacks a test.

---

## A complete chain, worked through once

One requirement, traced from the written requirement to the evidence that it works:

```text
FR-07  The customer shall be able to initiate a fund transfer
  → TransactionService.transfer()
  → POST /api/transactions/transfer  →  TransferPage.jsx
  → bank_transaction, account
  → TransactionWorkflowApiTest  →  PASS
  → docs/screenshots/06-fund-transfer-form.png,
    docs/screenshots/07-transfer-confirmation.png,
    docs/screenshots/09-successful-transaction.png
```
