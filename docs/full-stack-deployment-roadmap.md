# Full-Stack Deployment Roadmap

**A future plan. Nothing in this document has been executed.**

---

## Current status

```text
Current public deployment:
  Frontend showcase  →  GitHub Pages   (live, simulated data, no backend)

Future:
  React frontend     →  public hosting
  Spring Boot API    →  backend hosting
  MySQL              →  managed database
```

The backend and database source are published in this repository, but neither is deployed. That is
deliberate: the application is being polished first, and full-stack deployment happens afterwards.

No hosting provider has been selected, and none should be selected or purchased as part of this
plan without an explicit decision.

---

## Why not deploy the backend yet

- A public API backed by a real database is a standing operational commitment — secrets to rotate,
  a database to back up, a service to keep patched.
- Several security gaps are known and listed in [security.md](security.md#9-known-gaps). Token
  revocation and rate limiting matter more once the API is publicly reachable.
- The schema is currently managed by Hibernate `ddl-auto`. Deploying before migrations exist means
  the first schema change on live data is unversioned and irreversible.

The frontend showcase gives the portfolio value of a live, explorable interface without any of
that.

---

## What full-stack deployment will require

### 1. Backend hosting

A platform that can run a JVM process with configurable environment variables, HTTPS, and a
health check. Requirements rather than a product choice:

- Java 21+ runtime
- Environment-variable configuration (no secrets in the image or repository)
- A health endpoint the platform can poll — `/actuator/health` already exists
- Deployment from a built artifact or a container image
- Logs retrievable without SSH

### 2. Managed database

- MySQL 8 compatible
- Automated backups with a defined retention period
- Reachable only from the application tier — never exposed to the public internet
- A dedicated application account with rights on the application schema only, not server-wide
  administrative rights

### 3. Environment secrets

Every value in `.env.example` supplied through the platform's secret store, never committed:

- A freshly generated `OBFDS_JWT_SECRET` (minimum 32 characters — the application refuses to start
  otherwise)
- Database URL, username and password
- `OBFDS_SEED_DEMO_DATA=false` so the fictional dataset is not created in a real environment
- `OBFDS_CORS_ALLOWED_ORIGINS` set to the exact deployed frontend origin

### 4. HTTPS

TLS terminated at the platform edge or a reverse proxy. No plain HTTP outside the private network.

### 5. CORS

Currently restricted to the configured origin and never a wildcard. On deployment this becomes the
real frontend origin. The frontend build's `VITE_API_BASE_URL` must match.

### 6. Production profile

The `mysql` profile exists. Before deployment it needs:

- `ddl-auto` changed from `update` to `validate`, with schema changes owned by migrations
- Logging levels reviewed
- `open-in-view` already disabled

### 7. Database migrations

Replace `ddl-auto` with **Flyway** (or Liquibase):

1. Generate a baseline migration from the current entity model
2. Add it as `V1__baseline.sql`
3. Set `ddl-auto: validate` so Hibernate checks the schema instead of changing it
4. Every subsequent change becomes a numbered, reviewed migration

This is the single most important prerequisite. It should be done **before** the first deployment,
not after there is live data.

### 8. Monitoring

- Poll `/actuator/health`
- Alert on error rate and on database connectivity
- Watch the count of `LOGIN_FAILURE` audit rows per user — repeated failures are a security signal
- Watch the number of fraud cases awaiting a decision

### 9. Logs

- Centralised collection, retained for a defined period
- Application logs kept separate from the `audit_log` table, which is business evidence rather than
  diagnostics

### 10. Backup

- Scheduled full backup of the schema to storage separate from the database host
- A defined retention policy
- **A rehearsed restore.** A backup that has never been restored is an assumption, not a backup

### 11. Health checks

`/actuator/health` is already public and unauthenticated for this purpose. Only the health endpoint
is exposed; metrics, environment and bean endpoints stay closed.

### 12. CI/CD

Extend the existing workflows:

- Build and publish the backend artifact or image on a tagged release
- Run migrations as a deployment step
- Deploy the frontend with `VITE_API_BASE_URL` pointing at the deployed API
- A smoke check after deployment: health UP, one login, one transfer

### 13. Domain (optional)

If a custom domain is used: DNS, a certificate, and `OBFDS_CORS_ALLOWED_ORIGINS` plus
`VITE_API_BASE_URL` updated to match.

---

## Security work to complete first

From [security.md](security.md#9-known-gaps), in priority order for a publicly reachable API:

1. **Token revocation** — short-lived access tokens with refresh, or a revocation list, so
   disabling a login takes effect immediately
2. **Rate limiting** on authentication endpoints
3. **Security headers** (CSP, HSTS) at the proxy
4. **Dependency scanning** in CI
5. **A genuine out-of-band channel** for verification codes, replacing in-app delivery

---

## Suggested sequence

```text
1.  Add Flyway migrations, switch ddl-auto to validate
2.  Add token revocation and rate limiting
3.  Containerise the backend
4.  Extend CI to build and publish the artifact
5.  Provision a managed MySQL instance, run migrations
6.  Deploy the backend with secrets from the platform store
7.  Verify: health, login, transfer, audit trail
8.  Rebuild the frontend in API mode against the deployed API
9.  Deploy the frontend and set the real CORS origin
10. Enable monitoring, log collection and backups
11. Rehearse a restore
12. Keep the showcase build available as a no-backend fallback
```

---

## What stays as it is

The frontend showcase mode is **not** temporary scaffolding to be removed. It stays useful after
full-stack deployment: it lets the interface be explored with no backend running, it survives
backend downtime, and it keeps the repository demonstrable to anyone who clones it without setting
up a database.
