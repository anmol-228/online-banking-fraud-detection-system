# Operations runbook

How to run, verify, back up and recover the system. Each step says what to run, what you should see,
and what evidence it leaves. A step that has not been executed on the stated build is labelled
**NOT YET RUN**; nothing here claims more than was actually done.

The system exists in three modes:

| Mode | What it is | Status |
|---|---|---|
| **A. Hosted** | Backend and MySQL on a hosting provider, front end on a static host | **Not deployed.** See [deployment](deployment.md). Nothing is published until the owner approves it. |
| **B. Local, offline** | MySQL, backend and front end on one Windows machine, all bound to loopback | Implemented; **loopback-local verified** (socket audit, end-to-end run, scratch-database browser test). The **physical adapter-off run is still pending** (§1.6) |
| **C. Recovery bundle** | Backups, hashes and instructions to rebuild Mode B on another machine | Backup and restore proven; second-machine rebuild **NOT YET RUN** |

---

## 1. Mode B: the single-machine installation

### 1.1 What it consists of

```
<kit>\
  app\obfds-backend-1.0.0.jar      the service (also contains the migrator)
  web\dist\                        the built front end (served by a tiny static server)
  config\jwt.secret                random signing secret, created on first start (never in Git)
  scripts\*.ps1                    the scripts in ops/local-windows
  backups\                         dumps, their SHA-256 and a "verified" note each
<kit>\..\mysql-...\                MySQL 8.4 (loopback only, X protocol off)
<kit>\..\obfds-local-db.env        DDL account used ONLY by the migrator and tests   (owner-readable, not in Git)
<kit>\..\obfds-app-db.env          DML-only account used by the running service      (owner-readable, not in Git)
<kit>\..\mysql-root.env            root, used only by backup/restore scripts          (owner-readable, not in Git)
```

Three database accounts, three purposes:

| Account | Rights | Used by |
|---|---|---|
| `obfds_user` | everything on the OBFDS schemas | the **migrator** and the automated tests |
| `obfds_app` | `SELECT, INSERT, UPDATE, DELETE` only | the **running service** (it cannot create, alter or drop a table) |
| `root` | administrator | backup, restore and database creation scripts |

### 1.2 First-time setup

1. **Create the runtime account** (idempotent): `ops\local-windows\create-app-db-user.ps1`.
2. **Create or adopt the schema**, always through the migrator (`migrate.ps1`):
   - empty database → `migrate.ps1 -Command migrate`. Applies V1 (baseline) and V2.
   - existing database created before versioned migrations → `migrate.ps1 -Command baseline -Confirm`
     (takes a *verified* backup first, writes one history row, changes no data), then
     `migrate.ps1 -Command migrate`.
   - `migrate.ps1 -Command info` and `validate` are read-only and may be run at any time.
3. **Start everything:** `scripts\start-all.ps1` (MySQL → backend → front end → browser).
   The backend prints how long readiness took, the address it is bound to, the database connection
   (`obfds_app -> obfds`) and the schema version.

What the start-up refuses to do, deliberately:

- start with no signing secret, the published development secret, fixed demo passwords or open
  registration on anything other than a loopback-only, local-database run;
- start if the database is behind this build's schema version, or if Hibernate finds the tables do not
  match the entities (it never changes the schema itself);
- start if the web-server thread count is more than half the connection pool (this is what prevents the
  burst failure described in [security](security.md));
- start on a port that is already held by another program (it names the owner instead of quietly using
  an in-memory build that would forget your data).

### 1.3 Daily use

| Task | Command |
|---|---|
| Start all | `scripts\start-all.ps1` |
| Stop all (MySQL last, gracefully) | `scripts\stop-all.ps1` |
| What is running, and on which addresses | `scripts\status.ps1` |
| Open the application | http://localhost:5173 |

Never stop MySQL by killing the process; always use `mysql-stop.ps1` (it flushes InnoDB).

### 1.4 Backup and the proof that it restores

`scripts\backup.ps1 -DatabaseName obfds` does four things and prints the result of each:

1. `mysqldump` with a consistent snapshot → `backups\obfds_<timestamp>.sql`;
2. a SHA-256 next to it;
3. restores the dump into a scratch database and compares `CHECKSUM TABLE` for **every** table;
4. drops the scratch database and writes `<file>.verified.txt` (`VERIFIED … tables_equal=14/14`).

It never overwrites an earlier backup. A backup that was not restored is not counted as a backup.

**Rolling back** (for example after a bad migration): stop the backend, restore the verified dump into
an **empty** database (never over a live one), point the service at it, start. The drill in
[§4](#4-evidence-from-this-build) did exactly this and showed byte-for-byte equal data.

### 1.5 Verifying the whole system

| Check | Command | Meaning |
|---|---|---|
| End to end, 45 checks over real HTTP | `scripts\demo-verify.ps1` | Builds a **fresh** scratch database (migrator, then the app's own seeding), starts a second backend on port 18081 as the DML-only account, runs `verify_runtime.py`, then deletes both. The live database is not used. |
| Loopback-only sockets | `scripts\offline-check.ps1` | Lists every TCP socket owned by MySQL, the backend and the front end; passes only if every one is loopback. |

`demo-verify.ps1` uses a fresh database on purpose: the checks assert exact balances and report totals and
the velocity rule looks at the last ten minutes, so they cannot hold on data other runs have changed.

### 1.6 Physical offline proof (**NOT YET RUN**)

The socket audit above shows nothing needs the network; it is **not** the same as running with the network
off. The owner-run procedure, to be recorded on video or in a log:

1. Start the stack and run `status.ps1`. Disable the Wi-Fi adapter and unplug Ethernet, both visible on screen.
2. `Test-NetConnection 1.1.1.1 -Port 443` must report `False`.
3. Run `start-all.ps1` from a stopped state and time it to a working login.
4. Run `demo-verify.ps1` (expect 45/45), make a transfer in the browser, and run the SQL below.
5. Kill and restart the backend; confirm the data is still there. Reconnect the network.

Do not run this from a remote assistant session: it would cut the session itself.

### 1.7 The evaluator's SQL session

Run with `scripts\mysql-cli.ps1` (opens the client as the application user).

```sql
SHOW DATABASES;
USE obfds;
SHOW TABLES;                                   -- 13 tables plus flyway_schema_history
DESCRIBE bank_transaction;
SELECT version, description, success FROM flyway_schema_history;   -- the migration history
SELECT reference, amount, status, risk_level, risk_score FROM bank_transaction ORDER BY id DESC LIMIT 5;
SELECT reference, status, customer_response, review_due_at FROM fraud_case ORDER BY id DESC LIMIT 5;

-- A controlled, non-financial INSERT: a general notice for a seeded customer. It appears in that
-- customer's Notifications page in the browser, and survives a restart of both the backend and MySQL.
INSERT INTO notification (created_at, message, read_flag, related_reference, title, type, customer_id)
SELECT UTC_TIMESTAMP(6), 'Inserted directly in SQL to show the data is really in MySQL.', b'0',
       NULL, 'Direct SQL notice', 'GENERAL', id
FROM customer WHERE customer_number = 'CUST10000001';
```

**Do not edit a balance directly and present it as a transfer.** A transfer is the API operation: it takes
row locks, checks funds and reservations, scores risk, writes the audit trail and the notification in one
transaction. A hand-edited balance does none of that and would (correctly) leave the ledger inconsistent
with the transaction list.

### 1.8 Troubleshooting

| Symptom | Cause and action |
|---|---|
| Backend refuses to start with "startup safety guard refused" | Read the listed reasons; each names the setting. In Mode B this usually means the wrong profile (`mysql,localdemo` is required). |
| "Schema-validation: missing column" | The database is older than the build. Run `migrate.ps1 -Command migrate` (backup is taken first). |
| "no migration history" / "schema is at version N but this build needs M" | Run `migrate.ps1 -Command info`; baseline an old database, then migrate. |
| Port 8080 or 5173 already in use | The script names the owning program. Stop it; do not start a second copy. |
| Login returns `429` | The throttle locked that username from that address for a short time (30 s, doubling). Wait for `Retry-After`. |
| A held transfer's funds look reserved | Only holds whose code has not expired reserve funds; an expired hold frees them immediately and is closed on the account's next use. |
| `mysql-start.ps1` says the port is in use | Another MySQL is running. Check `status.ps1`. |
| Node cannot find a module when starting the front end | The `web\node_modules` folder is missing; reinstall it from `web\package.json` once. |

---

## 2. Fraud workflow, as an operator sees it

- **LOW** transfers settle immediately. **MEDIUM** transfers wait for a six-digit code (shown on the
  customer's Notifications page: there is no SMS gateway). **HIGH** transfers open a case.
- A **MEDIUM** hold whose code expires ends `FAILED` ("verification expired, transfer not sent"), frees the
  funds and closes its alert. It is not `BLOCKED`: nobody judged it fraudulent.
- A customer can **cancel** a held transfer (`FAILED`, "Cancelled by customer"). A case for it is closed
  with a remark saying this is not a fraud finding.
- For a **HIGH** hold the customer can state "this is mine" or "this is not mine". It is recorded once (a
  "not mine" can replace a "mine", never the reverse), shown to the analyst as an **unverified same-session
  statement**, and never moves money. After "not mine" the analyst cannot approve.
- Each held case shows a **review target** time. It is a target, not a deadline: nothing is released or
  escalated when it passes.
- An analyst can **export evidence** (JSON): masked account numbers, customer number only, no contact
  details, no free text of any kind (analyst remarks and the status-reason sentence are omitted; only a fixed
  outcome code and the remark length are exported), a SHA-256 hash that is also recorded in the audit trail.
- An analyst can prepare a **mail draft** to one of the team mailboxes the administrator configured in
  `obfds.escalation.recipients`. **The system sends nothing.** The user sends the draft from their own mail
  program and can then record "I sent it", which is shown as *self-reported*. Mailboxes of authorities,
  regulators and banks are refused at start-up. For real victims, the help page lists the official
  channels; nothing is submitted to them from here.

Example to demonstrate a genuine transfer that is held and resolved: as `ravi.kumar`, save a new payee, then
send 55,000 to it. It scores 60 (HIGH_AMOUNT 35 + NEW_BENEFICIARY 25) and is held. Open it, choose "This
transfer is mine", then as `analyst.fraud` open the case, read the reasons and the statement, and approve.
The audit trail and the evidence export show the whole sequence. Repeat with "This is NOT my transfer" to show
approval being refused and the block leaving the balance untouched.

---

## 3. Public deployment (Mode A): what is **not** done

Nothing is deployed and nothing is published. `FULL_STACK_PUBLIC_DEPLOYMENT` remains deferred.

Before the owner can authorise a release, these remain, and need the owner:

| Item | Who |
|---|---|
| Create the provider accounts (database, backend host, static host); confirm in each account UI that no card is required | owner |
| Provide the allow-listed operations mailbox (never invented) | owner |
| Run the provider experiments: TLS to the database, cold-start time, sleep/resume behaviour, free-tier limits | assistant, with the owner signed in |
| Final go/no-go to publish | owner |

A public build must be started with the `public` profile and these values from a secret store, and refuses
to start otherwise: `OBFDS_JWT_SECRET`, the five `OBFDS_BOOTSTRAP_*_PASSWORD` values (unique, 12+
characters, none a published demo password), `OBFDS_CORS_ALLOWED_ORIGINS` (exactly one `https://` origin),
and a database URL with `sslMode=VERIFY_CA` or `VERIFY_IDENTITY`. Registration stays closed unless an
invitation code is set. The golden local demo database is never loaded into a public database.

---

## 4. Evidence from this build

Recorded under the private checkpoint folder and summarised in the review package; none of it contains a
password or a signing secret.

| Evidence | Result |
|---|---|
| Full automated suite on H2 and on real MySQL 8.4 (server-default REPEATABLE READ), per-class count gate | see [testing](testing.md) |
| Committed-data (race) suite repeated 5 times in fresh JVMs on MySQL | 13/13 each time; 0 InnoDB deadlocks recorded in a fixed-code run |
| Migration drill on a restored copy of the demo data | empty-database path, refused blind migrate, explicit baseline, V2 applied, data tables unchanged, runtime account denied DDL, service starts on the migrated copy, forged default-secret admin token rejected (HTTP 401), token survives a restart, restore of the pre-migration dump gives back identical data |
| End-to-end verification on a fresh migrated and seeded MySQL | 45/45 |
| Backup with restore proof | `CHECKSUM TABLE` equal for 14/14 tables |
| Loopback socket audit | 0 non-loopback sockets owned by the three demo processes |
| Physical adapter-off run; second-machine rebuild on JDK 21 | **NOT YET RUN** |
