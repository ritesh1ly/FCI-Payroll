# FCI Payroll (FCILekha) — build instructions for Claude

Read this before every task. Adapted from `docs/requirements/tools/devkit_overlay/CLAUDE.md` to this repository.
Progress lives in `docs/tracker/PROGRESS.md`, every open item across modules in `docs/tracker/PENDING.md`, every answer the owner
gave to an open question (for FCI to verify) in `docs/tracker/OWNER_DECISIONS.md`; per-module plans in `docs/plan/`.
Then read `docs/engineering/DEVELOPER_PLAYBOOK.md` (patterns to reuse, traps already hit, test rules). Several developers
build different modules: when you learn something another module team would need, add it there (and to its learnings log)
in the same change, and say so in the PR description.

## 1. Sources of truth

Order when sources conflict (the KB precedence, confirmed by the project owner: newest wins):
1. **SRS v0.5 Final** — `docs/requirements/src/FCI_Payroll_SRS_v0.5_Final.md` (line numbers are what the KB cites).
2. **Target DDL v4.0** — `docs/requirements/src/FCI_Payroll_Target_DDL_v4.0.sql` (290 tables). Schema of record.
3. **FRS V0.5**, **HLD v0.6** — `docs/requirements/src/` — detail only where not superseded.

The `.docx` originals of the SRS, FRS and HLD sit beside their `.md` conversions (added 2026-10-23). Their text matches the `.md`
(same requirement IDs and table count, checked 2026-10-23): read and cite the `.md`. The originals add only figures: the SRS
screen mockups (each screen in View and Edit/Entry mode), the FRS workflow diagrams (each restated as steps in the text) and the
HLD architecture figures. **Wireframe v6.1** is `docs/requirements/src/FCI_Payroll_Wireframes_v6.1_Dev.html`: a **frozen requirement** for every
screen (owner 2026-11-06, OD-121): each object of a wireframe screen (menu group and order, cards, tiles, filters, fields, columns
with source badges, buttons with their disabled reason, tabs, dialogs) is built as drawn, with no deviation; rules, rates and
statuses still come from the SRS / DDL, and a wireframe object that contradicts them is built as drawn and listed in PENDING for FCI.
Past parity reports: `docs/quality/wireframe-parity/` (history: the parity Playwright tests and their tooling were removed, OD-157). FRS v0.4 and `docs/config/*.json` named by the dev kit are **not in this repository**. Where the KB
says it was not checked against them, treat that as a known gap; do not guess their content.

**Knowledge base** (derived aid): `docs/requirements/kb/` — module specs, data dictionary, conflict log (CF-nn), open items (OQ-n,
DB-n), scenarios and business queries (Q-nnn). If a KB statement differs from the SRS or DDL, the source wins: add
`// OPEN-QUESTION:` citing the CF / Q number and list it in the tracker.

**Never invent a business rule, rate, default or status.** If the SRS/DDL are silent: stop, add `// OPEN-QUESTION:`, record it in
`docs/tracker/PROGRESS.md`. OQ items and values marked `>> SETTING <<` are rule-engine settings left **unseeded** so the engine
refuses to compute (DDL v4.0: "a seeded guess is worse than an empty value"; KB CF-49). Demo values go in `db/seed/` (profile
`demo`) only.

## 2. Scope guard

In scope: four payroll engines (Employee IDA / CDA / FND; Departmental Labour; DPS Labour; NWNP Labour), CPF / PF / VPF in full,
tax (TDS, PT, Form 16, 12BA, 12BB, 10E, 24Q), loans, F&F, eSeva, NPS/MHS **as received from HRMS** (no calculation).
Out of scope — do not build: pension, LIC annuity, PRMS, NPS CRA integration, CPF trust administration. Nothing Kotwal-specific
(CF-46, Q-014).
HRMS-owned data is never keyed in Payroll (no employee creation; CPF code / member ID, parent zone, UAN, EPS no., NPS / MHS amounts).
A pay-effect field changes only through an I-21 task. Only exceptions: the two X-07 fallback uploads. Labour are not in HRMS.

## 3. Non-negotiable rules

| # | Rule | Implementation here |
|---|---|---|
| R-DEL | Never delete business records | Status / new version; repositories extend `Repository`, never `CrudRepository`; no DELETE endpoint |
| R-MC | Maker-checker on every controlled change | `platform.approval.MakerChecker.submit(...)`; effect in an `@EventListener` on `ApprovalDecided` (same transaction); routes are `approval_workflow_action/_level` rows, never code |
| R-OTP | OTP on exactly the 18 `otp_challenge.gated_action` codes | `workflow.api.Otp` / `OtpGatedAction`; never store the code; never name an SMS provider. `fci.api.otp.required` 'Y' (default, UAT and production; StartupGuards refuse 'N' there) or 'N' (development and local machines: critical activities confirmed without a code, a challenge still recorded); password reset always asks |
| R-AUD | Audit every change in the same transaction | `audit.api.AuditService.record(...)` with before / after, reason, approval id |
| R-RULE | Rates, limits, slabs are configuration | rule engine (`rule_definition` / `rule_version`), `rate_set`, `other_parameter`; unseeded ⇒ "not configured" error |
| R-SRC | Field-source markers ENT, CALC, HRMS, RULE, SYS, ESS, INTF | HRMS / CALC / SYS read-only on screens and refused by the API |
| R-ASCII | DB character set US7ASCII | server-side printable-ASCII validation; Hindi only via `label_master` / i18n |
| R-MONEY | `BigDecimal` only, round once at the defined point | `kb/02-cross-cutting/rounding-money.md` |
| R-RBAC | Access is configuration | `@PreAuthorize("@authz.gate('<screen>','<action>')")` + data-scope checks in the service |
| R-PII | Masked data only | `platform.logging` masking; PAN / bank / Aadhaar masked in responses; no real data in fixtures or logs |
| R-INT | Integration retries are settings | Resilience4j with per-interface settings; visible on X-03 |

## 4. Stack and structure (as built)

- Backend: Java 25, Spring Boot 4.1 (Jackson 3, Spring Batch 6), Gradle (`build-logic` convention plugins), one WAR on Tomcat 11,
  profiles `online` / `batch` (ADR-0025).
  Modular monolith `in.gov.fci.payroll.<module>` with layers `web → orchestration → business`, `persistence`, `batch`, `api`;
  boundaries enforced by Spring Modulith + ArchUnit (`:backend:architectureTest`). See `docs/ARCHITECTURE.md`.
- Database: H2 (interim, ADR-0019; all test suites) and Oracle 19c target (local runs on Oracle 26ai, profile `oracle`,
  `docs/db/ORACLE_LOCAL.md`; SQL kept to 19c by `scripts/oracle_19c_check.py`); Flyway scripts in `db/migration/{common,h2,oracle}` with rollbacks in
  `db/rollback/{h2,oracle}`; `ddl-auto=validate`. New v4.0 tables are copied verbatim from the target DDL; a missing parent FK is
  omitted and listed as a deviation in `docs/db/DDL_V4_ALIGNMENT.md`.
- Frontend: Next.js 16 SSR (`frontend/`), modules mirror the backend; Vitest unit tests; Playwright E2E in `frontend/e2e`.
- New business modules get their own package with `package-info.java` `@ApplicationModule(allowedDependencies = …)`.

## 5. Traceability

Cite requirement IDs in Javadoc and test names: `/** SRS-G-10, BR-CYC-010; screen I-20; DDL off_cycle_payment */`,
test `SRS_G_10_duplicateCheckFailureBlocksDisbursement`. `python scripts/trace_check.py` fails if code cites an ID that is not in the
KB registry (`docs/requirements/kb/05-mappings/requirement-registry-*.md`).

## 6. Definition of done (per feature)

1. Rules cite IDs; trace check passes.
2. Unit tests for every rule and validation, named after the ID, incl. the spec's negative cases.
3. Controller / integration tests: wrong role, out-of-scope office, maker approving own item, missing OTP, HRMS-owned field edit.
4. Screen per spec: field sources, read-only fields, disabled-with-reason actions, en + hi labels.
5. Playwright E2E for the happy path and the key negatives.
6. `./gradlew check` green (Spotless, `-Werror`, Jacoco hard gates line 97 % and branch 95 %, always aim at 100 %, architecture; PIT and Stryker 95 %), SpotBugs / FindSecBugs no new
   high findings, no secrets, `npm run check` green.
7. KB review (Prompt 7, `docs/requirements/README.md` prompt library) shows no blocking gap; scenarios covered or marked blocked.
8. Tracker updated; open questions listed; `docs/tracker/PENDING.md` updated (closed items removed, new ones added).

## 7. How to work

- One module at a time, in the build order of `docs/tracker/BUILD_ORDER.md` (dependencies: hard / port); plan first
  (`docs/plan/<NN-module>.md`), then tests + code. **Before starting a module run `python scripts/check_build_order.py <NN>`**;
  on a WARN (a hard dependency not built) stop and ask the owner whether to build the dependency first, go on with a port, or
  scope the feature out, even when the module was named in the request, and record the answer in the plan.
- Ask the user before: changing CI, dropping or altering existing tables, anything outside the module in hand.
- Engines are deterministic and re-runnable: same inputs + rule versions ⇒ same result; resume only with unchanged
  `parameter_hash` / `rule_version_set_hash` (BR-CYC-011…017).
- Never edit `docs/requirements/` (baseline). Never paste the consolidated KB into a prompt.
