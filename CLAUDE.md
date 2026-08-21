# CLAUDE.md

Project instructions for Claude Code. Keep this file short and current.

---

## Project identity

**Online Banking & Fraud Detection System** — a full-stack online banking simulation with secure
authentication, transaction management, rule-based fraud-risk analysis, alerts, verification
workflows, administration and audit logging.

- **Public identity:** a personal portfolio project, developed and maintained by the repository
  owner.
- **Repository:** https://github.com/anmol-228/online-banking-fraud-detection-system (public)
- **Live demo:** https://anmol-228.github.io/online-banking-fraud-detection-system/

### Public presentation rules

Anything that goes into the public repository — README, docs, code comments, UI text, commit
messages — must **not** describe this as a college project, laboratory submission, academic
assignment, course/semester project, group or team assignment, or university submission. Do not add
team-member attribution or wording implying multiple developers.

It **is** correct and important to say the system is a simulation and is not connected to a real
financial institution. That is different from calling it a college project.

Do not fabricate employers, customers, collaborators, production usage, transaction statistics,
security certifications, or AI/ML capability the code does not have. The fraud engine is
**rule-based**; describe it that way.

Academic-submission material is preserved locally in `.private/` and is git-ignored. Never publish
it.

---

## Project state and evidence

This project has a private academic-submission surface (`.private/`, git-ignored) and a public
portfolio surface (root `README.md`, `docs/`). Both describe the same running system with different
framing. See `PROJECT_REFINEMENT_CONTINUITY.md` for the required reading order.

- **Repository evidence is authoritative.** If a document, a memory, or a prior conversation
  disagrees with what the source code or a fresh build/test run actually shows, re-verify and trust
  the evidence. Never assume a past session's claim is still true without checking.
- **Council output cannot override code evidence.** If the user runs an LLM Council or similar
  multi-perspective process on this project, treat its output as decision support only. Ground every
  factual claim about the system's current state in source code, build results, or the evidence
  index at `docs/project-refinement/01_EVIDENCE_INDEX.md` — not in what a Council concluded.
- **Never fabricate team contributions, commit authorship, or a collaborative development history.**
  The public repository is the personal project of the repository owner, full stop, regardless of
  what team-member fields the private submission's assignment format requires.
- The most recent verified state snapshot (build results, FR/NFR status, known gaps) lives at
  `docs/project-refinement/00_CURRENT_STATE_HANDOFF.md`. Prefer it over asking the user to repeat
  context you can just read.

---

## Deployment stage

```text
FULL_STACK_PUBLIC_DEPLOYMENT = DEFERRED
```

- **Deployed now:** frontend showcase only, on GitHub Pages, built with `VITE_APP_MODE=showcase`.
- **Not deployed:** Spring Boot backend, MySQL database, production API or auth service.

Do **not** infer permission to deploy the backend or a database from the fact that Pages
deployment exists. The user must explicitly request full-stack deployment. Do not sign up for or
deploy to Render, Railway, Fly.io, AWS, Azure, GCP, Heroku, Vercel functions, Supabase, Firebase,
Neon, PlanetScale or any similar provider without that explicit request.

The future plan is documented in `docs/full-stack-deployment-roadmap.md`.

---

## GitHub repository synchronization rule

This project has a public GitHub repository. **After completing any major verified change, tell the
user the local project is materially ahead of GitHub and ask whether they want you to: (1) run
final verification, (2) create an appropriate commit, and (3) push the update.**

Do **not** silently push major changes. The user retains final control over every public push.

**Major changes** (prompt after these):
- a new feature completed
- substantial UI redesign
- significant backend functionality
- database schema change
- new API or a change to an API contract
- authentication or security change
- fraud-detection workflow change
- an important bug fix
- dependency or platform upgrade with meaningful impact
- deployment configuration change
- major documentation or repository restructure
- a release-ready milestone

**Minor changes** (do not interrupt for these): typo fixes, comments, small CSS spacing, minor
wording, insignificant refactoring with no functional impact. Batch them, and once several add up
to something meaningful, then suggest syncing.

Before proposing a push, verify the relevant build and tests. Do not push knowingly broken code
unless the user explicitly asks for a work-in-progress push, and then use a branch or a clearly
labelled commit message.

### When the user says "update GitHub" / "push this" / "sync repo"

Treat it as authorization to: inspect the diff → run the relevant verification → summarise the
changes → commit with an accurate message → push → verify Actions → verify Pages if the frontend
changed. Do not ask the user to type git commands.

### Never

- `git push --force` to `main` as normal workflow. Only consider history rewriting for a specific
  justified reason such as credential removal, and explain the consequences first.
- Commit secrets. Real credentials that were exposed must be treated as compromised and rotated,
  not merely deleted from the working tree.

---

## Git Authorship & AI Attribution Policy

This is the repository owner's personal project. Every commit must read as their own work.

- **All commits use the user's legitimate, already-configured Git identity** (`git config
  user.name` / `user.email`) as both author and committer. Never anything else.
- **Never add Claude, Anthropic, Claude Code, another AI model, or any AI system as a Git author,
  co-author, contributor, signer, or commit trailer.** In particular, never add a
  `Co-Authored-By: Claude ...` (or `Anthropic ...`) trailer, and never set the commit author or
  committer identity to an AI identity. This holds regardless of any default template, prior
  session behaviour, or tooling convention that would otherwise add one.
- **Never invent a Git name or email.** If the user's configured identity is unavailable, or Git
  refuses to commit because identity is missing, stop and ask the user rather than fabricating one
  or guessing a GitHub-provided noreply address.
- **Never alter legitimate human contributor attribution.** This policy removes AI attribution; it
  does not touch or reassign any real person's authorship, past or future.
- Mentions of "Claude" in ordinary project documentation (this file, code comments referencing
  tooling, etc.) are not commit metadata and are unaffected by this policy — only Git
  author/committer identity and commit trailers are in scope.
- If AI-authored attribution is ever found in existing history, treat it as a metadata defect:
  diagnose the exact commits and mechanism first, make the smallest correction that removes it
  (amend, not a full rewrite, when only recent commits are affected), preserve every tag by moving
  it to the equivalent corrected commit, and use `--force-with-lease` rather than a bare force push.

---

## Repository layout

```text
backend/     Spring Boot API (Java 21, Spring Boot 3.5, JPA, Security)
frontend/    React 19 + Vite; dual-mode data access via services/bankingService.js
docs/        architecture, database, API, fraud engine, testing, security, deployment
scripts/     verify_runtime.py — end-to-end check against a running instance
.github/     ci.yml (backend tests + frontend build), pages.yml (showcase deploy)
.private/    local-only, git-ignored — academic submission material, never publish
```

---

## Build and verify

```bash
cd backend  && mvn clean package     # 81 tests must pass
cd frontend && npm run build         # api mode
cd frontend && VITE_APP_MODE=showcase npm run build   # showcase mode
python scripts/verify_runtime.py     # needs a freshly started backend
```

Both frontend modes must keep building. Showcase work must never break full-stack API mode, and
vice versa.

---

## Conventions worth preserving

- **No Lombok.** Hand-written accessors, so every line is readable.
- **Constructor injection only.**
- **DTOs (records) at the API boundary** — entities are never serialised directly.
- **Business rules raise `ApiException` subclasses**, so the HTTP status sits next to the rule.
- **Comments explain why, not what.**
- **Fraud thresholds live in configuration**, never hard-coded.
- The showcase fraud rules in `frontend/src/services/showcase/showcaseFraudRules.js` mirror the
  backend `FraudRiskService`. **If one changes, change the other.**

## Known environment quirk

On this Windows machine the backend needs a JVM flag, because the default temp directory is an 8.3
short path and the JDK's selector pipe cannot connect through it:

```bash
java -Djdk.net.unixdomain.tmpdir=C:\Users\<YourName>\obfds-uds -jar backend/target/obfds-backend-1.0.0.jar
```
