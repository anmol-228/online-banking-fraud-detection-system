# Database Design

![Entity relationship diagram](diagrams/05_er_diagram.png)

---

## 1. Entities

Twelve entities plus one join table.

| Table | Purpose |
|---|---|
| `application_user` | Login identity: username, BCrypt hash, enabled flag |
| `user_role` | A grantable role, stored as a row so it can be changed at run time |
| `application_user_role` | Many-to-many join |
| `customer` | Banking identity of a customer |
| `account` | A simulated bank account |
| `beneficiary` | A saved payee |
| `bank_transaction` | A fund transfer |
| `verification_request` | An additional-verification challenge |
| `fraud_alert` | Alert raised for a suspicious transfer |
| `fraud_case` | Investigation opened from an alert |
| `dispute` | Customer complaint about a transaction |
| `notification` | Message to a customer |
| `audit_log` | Append-only record of important events |

`Transaction` maps to `bank_transaction` because `TRANSACTION` is a reserved word in MySQL and
several other dialects. `Notification.read` maps to `read_flag` for the same reason.

---

## 2. Relationships

```text
ApplicationUser  *---*    UserRole            (via application_user_role)
ApplicationUser  1---0..1 Customer            (staff log in but own no accounts)
Customer         1---*    Account
Customer         1---*    Beneficiary
Customer         1---*    Notification
Customer         1---*    Dispute
Account          1---*    Transaction         (as the source account)
Beneficiary      0..1---* Transaction         (null when the payee was not saved)
Transaction      1---0..* FraudAlert
Transaction      1---0..* VerificationRequest
Transaction      1---0..* Dispute
FraudAlert       1---0..1 FraudCase
```

`audit_log` has **no foreign keys** by design. Audit rows record what happened, including events
involving records that may later be deactivated or that never existed — a login attempt with an
unknown username, for example. Linking by `username` and `entity_reference` as plain values keeps
the trail complete and independent, which is the whole point of an audit trail.

---

## 3. Three design decisions

### Login identity is separate from banking identity

Bank staff — administrators, fraud analysts, operations officers — must log in but do not own bank
accounts. Merging login and banking identity into one table would mean every staff member carried a
row in a table called `customer`, with `customer_number` either null or meaningless, and
`account.customer_id` would point at a table that no longer means "customer".

Separating them gives a clean one-to-one that is simply absent for staff, a foreign key from
`account` to a table where every row genuinely is a customer, and one place where authentication
concerns live.

### Verification challenges are their own entity

A challenge has state that does not belong on the transaction row: a hashed code, an attempt
counter, a maximum, an expiry and its own status. Keeping past requests also enables the
`REPEATED_FAILED_VERIFICATION` rule, which counts failures **across** transfers.

Only a BCrypt hash of the code is stored, exactly like a password.

### Disputes are their own entity

A complaint has its own lifecycle (`OPEN → UNDER_REVIEW → RESOLVED | REJECTED`), its own text, its
own resolution and its own handler. Putting those on `bank_transaction` would mix two unrelated
concerns and make it impossible to distinguish "this transfer was blocked" from "this transfer is
disputed".

---

## 4. Key table definitions

### `account`

| Column | Type | Constraints |
|---|---|---|
| `id` | BIGINT | PK |
| `account_number` | VARCHAR(20) | NOT NULL, UNIQUE |
| `customer_id` | BIGINT | NOT NULL, FK → `customer.id` |
| `account_type` | VARCHAR(20) | `SAVINGS` or `CURRENT` |
| `balance` | DECIMAL(15,2) | NOT NULL |
| `currency` | VARCHAR(3) | NOT NULL |
| `status` | VARCHAR(20) | `ACTIVE`, `BLOCKED`, `CLOSED` |
| `opened_at` | TIMESTAMP | NOT NULL |
| `version` | BIGINT | NOT NULL — optimistic lock |

`DECIMAL(15,2)` rather than a floating-point type: binary floating point cannot represent decimal
money exactly, and the errors accumulate. `BigDecimal` is used throughout the Java side.

### `bank_transaction`

| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT | PK |
| `reference` | VARCHAR(30) | UNIQUE — e.g. `TXN-20260819-726BBC` |
| `source_account_id` | BIGINT | FK → `account.id` |
| `beneficiary_id` | BIGINT | FK, nullable when the payee was not saved |
| `destination_account_number` | VARCHAR(20) | |
| `destination_name` | VARCHAR(120) | |
| `amount` | DECIMAL(15,2) | |
| `status` | VARCHAR(30) | `PENDING`, `PENDING_VERIFICATION`, `APPROVED`, `BLOCKED`, `FAILED` |
| `risk_level` | VARCHAR(10) | `LOW`, `MEDIUM`, `HIGH` |
| `risk_score` | INT | |
| `risk_reason` | VARCHAR(500) | Which rules fired, in words |
| `status_reason` | VARCHAR(250) | Why the status is what it is |
| `idempotency_key` | VARCHAR(80) | UNIQUE, nullable |
| `initiated_by` | VARCHAR(60) | |
| `created_at` / `updated_at` / `completed_at` | TIMESTAMP | |

Indexes on `source_account_id`, `status` and `created_at` — the three columns the history query,
the reserved-amount sum and the velocity rule filter on.

### `audit_log`

| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT | PK |
| `occurred_at` | TIMESTAMP | Indexed |
| `username` | VARCHAR(60) | Indexed |
| `roles` | VARCHAR(120) | Authorities held at the time |
| `action` | VARCHAR(60) | Indexed, from a fixed vocabulary |
| `entity_type` / `entity_reference` | VARCHAR | |
| `details` | VARCHAR(500) | Truncated safely if longer |
| `outcome` | VARCHAR(10) | `SUCCESS` or `FAILURE` |

---

## 5. Data integrity

| Concern | Mechanism |
|---|---|
| Referential integrity | Foreign keys on every relationship |
| No duplicate logins, emails, account numbers, references | Unique constraints |
| No duplicate payee for one customer | Composite unique on (`customer_id`, `beneficiary_account_number`) |
| One fraud case per alert | Unique constraint on `fraud_case.alert_id` |
| No duplicate transfer | Unique `idempotency_key` (honoured only for its owner and the same request) plus a 60-second duplicate window for requests with no key |
| Concurrent balance updates | Row locks taken first, in one global order (case, then accounts by id), under `READ_COMMITTED`; `@Version` optimistic locking remains as a second line of defence |
| Atomic settlement | Debit, credit and status change inside one `@Transactional` method |
| Over-commitment of funds | Available balance excludes amounts reserved by pending transfers, read only after the account row is locked; a hold whose verification code expired no longer reserves |
| Invalid state transitions | `APPROVED`, `BLOCKED`, `FAILED` are final; further changes rejected |
| Monetary precision | `DECIMAL(15,2)` and `BigDecimal`, never floating point |
| History stays readable | Beneficiaries are deactivated, never deleted |
| Audit immutability | Audit rows are only ever inserted and read |

---

## 6. Schema management

| Profile | Database | `ddl-auto` | Use |
|---|---|---|---|
| `dev` | H2 in-memory | `create-drop` | Local development; fresh seeded dataset every start |
| `test` | H2 in-memory, isolated | `create-drop` | Automated tests (the same suite also runs against MySQL, which the tests then create and drop) |
| `mysql` (with `localdemo` or `public`) | MySQL 8.4 | `validate` | A persistent installation. The runtime **never changes the schema** |

### Versioned migrations (Flyway)

The MySQL schema is owned by `backend/src/main/resources/db/migration`:

| Version | What | Notes |
|---|---|---|
| `V1__baseline.sql` | The full schema: 13 tables, 12 native `ENUM` columns, 12 `AUTO_INCREMENT` identity columns, all foreign keys and unique constraints | Derived from the schema Hibernate generated on MySQL. Two kinds of line were removed from the raw dump: statements that delete tables, and the **table-level** `AUTO_INCREMENT=<n>` counter (a snapshot of one machine's history). The **column-level** `id bigint NOT NULL AUTO_INCREMENT` is deliberately kept: JPA's `IDENTITY` generation depends on it |
| `V2__fraud_case_customer_response.sql` | Three nullable columns on `fraud_case`: `customer_response`, `customer_responded_at`, `review_due_at` | Additive: no default, no existing row changes, no `ENUM` altered. The previous build keeps running against it, so a code rollback does not need a database restore |

How it is applied, and why it is shaped this way:

- Flyway does **not** run inside the application (`spring.flyway.enabled=false`). The schema is changed only
  by the separate migrator (`com.sepro.obfds.migrate.Migrator`) with a DDL account. The service runs as an
  account that can only `SELECT/INSERT/UPDATE/DELETE`, and Hibernate's `validate` plus a startup version check
  refuse a database that is behind the build. The application does **not** claim to be running Flyway
  validation at start-up.
- `clean` does not exist in the migrator and Flyway's own clean is disabled. `migrate` over existing tables
  that have no history is refused by Flyway (so V1 can never be run over a populated database); adopting an
  existing database is an explicit `baseline` that needs a confirmation flag and writes one history row.
- H2 (`dev`/`test`) keeps `create-drop`; the V1 SQL is MySQL-specific.

What was verified on MySQL 8.4.13 (`SchemaMigrationMysqlTest`, 12 tests, and a drill on a restored copy of the
real demo data): an empty database migrates to V2; the identity columns generate ids; every `ENUM` column lists
exactly its Java enum's values; Hibernate `validate` accepts the migrated schema and the seeder inserts through
JPA; a populated pre-Flyway database is refused by a blind `migrate`, adopted by `baseline`, upgraded to V2 with
every other table byte-for-byte unchanged; an edited migration is detected (checksum); the service refuses to
start on an out-of-date or history-less database; restoring the pre-migration dump gives back identical data.
Flyway 11.7 officially tests MySQL up to 8.1 and logs a notice on 8.4; the combination is verified here, not
vendor-certified.

### New `fraud_case` columns

| Column | Type | Meaning |
|---|---|---|
| `customer_response` | VARCHAR(16) NULL | `CONFIRMED_MINE` or `NOT_MINE`; written once; evidence only |
| `customer_responded_at` | DATETIME(6) NULL | when it was recorded (UTC) |
| `review_due_at` | DATETIME(6) NULL | the review **target** (never enforced) |

All timestamps are stored in UTC. The unusual-hour fraud rule reads the clock hour in the configured zone
(`Asia/Kolkata`) from the application's own clock, never from the database server's session time.

---

## 7. Seed data

`DataSeeder` creates the five roles in every environment, and a fictional demo dataset according to
`obfds.seed.mode`: `demo-fixed` (the published demo passwords, isolated local runs only), `bootstrap-env` (the
same dataset with five passwords taken from the environment) or `none` (roles only). It seeds only when no
users exist yet, so restarting against MySQL does not duplicate data.

Seeded: 6 users (2 customers, 4 staff), 2 customers, 3 accounts, 3 beneficiaries.

Beneficiaries are backdated 30 days so they do **not** trigger the new-beneficiary rule — which is
what makes a payee added live during a demonstration trigger it visibly.

All names, account numbers and email addresses are invented. Email addresses use the reserved
`.example` top-level domain, which can never resolve to a real address.
