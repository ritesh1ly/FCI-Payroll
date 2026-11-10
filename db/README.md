# db/

Versioned database scripts of FCILekha Payroll, one source for every environment and both backend
modes. The build packages `migration/` and `seed/` into the WAR (`classpath:db/...`); Flyway runs
them in the **online** instance only (the batch instance has Flyway disabled and starts after it).

| Path | Content |
|---|---|
| `migration/oracle/V*.sql` | Target DDL v3.0 (scoped core) and the FCILekha platform tables, Oracle dialect |
| `migration/h2/V*.sql` | The same, ported to H2 (Oracle mode) for the interim database (ADR-0019) |
| `migration/common/V006__reference_seed.sql` | Vendor-neutral reference data (every environment) |
| `seed/V008_1__demo_seed.sql` | Obviously fake demo users and data, profile `demo` only |
| `rollback/oracle/U*.sql`, `rollback/h2/U*.sql` | One rollback script per version |
| `tools/ddl/`, `tools/seed/` | Generators of the H2 port and of the seed files |
| `install/h2/`, `install/oracle/` | The database **install kit** per vendor (numbered drop, DDL, compile, reference / demo / edge data, run-all wrappers), GENERATED from the Flyway end state by `./gradlew :backend:installKit` ([docs/db/INSTALL_KIT.md](../docs/db/INSTALL_KIT.md)); Flyway stays the schema of record |
| `tools/kit/` | `flyway_history.py`: where the migrations create, alter and drop each object (INSTALL_KIT.md section 1) |

`V009` is a Java migration (`backend/src/main/java/in/gov/fci/payroll/iam/persistence/V009__Bootstrap_system_administrator.java`): the first-deploy System
Administrator; its rollback `U009` revokes that access instead of deleting it (audit is append-only).

| Version | Change |
|---|---|
| V001–V004 | Organisation, employee, governance, RBAC (target DDL S02, S03, S05, S27) |
| V005, V005_1 | FCILekha extensions (personas, preferences, OTP, sessions, idempotency); H2 trigger ports |
| V006 | Reference data |
| V008_1 | Demo seed (profile `demo`) |
| V009 | First-deploy System Administrator (Java) |
| V010 | Single backend: token tables retired; Spring Session tables; job queue `batch_job_request` (BATCH_JOB_REQUEST lifecycle PENDING → PICKED → RUNNING → …), `batch_payroll_result` |
| V011 | Spring Batch 5.2 job repository (verbatim vendor schema) |
| V012, V012_1 | RBAC per-user screen access (`app_screen`, `app_user_screen_access`; OD-12), `system_parameter` (ADM-SYS-001); roles SUPER_USER and CPF_DIVISION (provisional), screens A-06/A-07 |
| V012_2 | Demo: `superuser.hq@`, `cpf.north@`, one approved sample override (profile `demo`) |
| V013, V013_1 | Batch module: `batch_job_control`, `batch_job_lock`, `batch_skipped_item`, `batch_job_audit`, `batch_arrear_result`; screen C-13 Batch Jobs (docs/BATCH_DESIGN.md) |
| V014, V014_1, V014_2 | Target DDL **v4.0** phase 1, governance and identity (docs/db/DDL_V4_ALIGNMENT.md): `approval_workflow_action` + `_level` replace `approval_authority` (dropped), `approval_decision`, queue / inbox level and escalation columns; `otp_challenge` on the 18 gated actions; `app_session` rebuilt with a token hash (open sessions end); `security_event`; RBAC, `app_user`, credential-unlock and employee HRMS columns; `org_zone` number bands dropped. V014 adds, V014_1 (common) moves the data, V014_2 drops and constrains |
| V015, V015_1, V015_2 | Target DDL **v4.0** phase 2, reference data and the rule engine: `reference_list/value`, `circular_order` (+ scope, link), `city_type_location_map`, `label_master`, `rule_type_reference`, `rule_definition`, `rule_version` (+ trigger), `rate_set(_line)`, `other_parameter`, `allowance_ceiling_norm`, `pay_component`, `gl_account_mapping`; A-07 moved into the rule engine (`rule_setting_bound`), `system_parameter` dropped |
| V016_1; demo V015_9, V016_2, V016_3 | Phase 3: catalogues of the dynamic master screens (reference lists STAFF_RATE_MASTER, SPECIAL_COMPENSATORY, OTHER_RATE_MASTER, OTHER_PARAMETER; codes only). Demo: the dual-role user `maker.checker@`, the design-pack sample rates / pay codes / GL accounts, the approver-only `rule.approver@` |
| V017, V017_1; demo V017_9 | Phase 4: `employee_lifecycle_event`, `employee_transfer`, `employee_number_history` (I-17, M-11), `app_user.deactivation_event_id` FK, reference list EMPLOYMENT_STATUS. Demo: employees 109001 (with the account `transfer.subject@`) and 109002, subjects of the event tests |
| V018, V018_1, V018_2; demo V018_9 | Phase 5: `payroll_calendar`, `payroll_cycle_instance`, `computation_run`, `computation_work_unit`, `payroll_result(_component)`, `arrear_event(_type)`, `arrear_detail` (C-02, C-13), forward-only cycle and result-immutability triggers; arrear event types; `batch_payroll_result` / `batch_arrear_result` dropped, job queue admits PAYROLL_COMPUTATION. Demo: calendar FY 2025-26 and 2026-27, pay code 131 CCA, DH001 a cycle unit of its own |
| V019 | `cache_region`: versions of the master-data cache regions (FCILekha extension; [CACHING.md](../docs/architecture/CACHING.md)): an approved change increments its region so every online and batch instance reloads it |
| V020_1; V020_2 (per vendor) | M-16 reference lists for every dropdown (13 lists, 65 values, generated from the i18n bundles) and their Hindi labels in `label_master` (Oracle: `UNISTR` escapes, US7ASCII database) |
| V021_1 | M-16 Reference & Combo List Administration: screen (override-protected), permissions, roles (System Administrator maker, HQ Payroll Checker APPROVE), workflow M16_CREATE / M16_EDIT; the four vocabulary lists become open lists |
| V022 (Java, `platform.persistence`) | Pay pattern aligned with v4.0: the inline FNB CHECKs of `employee.pay_pattern` and `pay_scale_band.scale_type` (database-named) found in the dictionary, dropped and replaced by named CHECKs with IDA / CDA / FND |
| V023 | `audit_event.location_id`: the office an audit row was recorded from (FCILekha extension), so A-05 follows the selected office; rows before V023 and without a session stay national (append-only: no backfill) |
| demo V024_9 | Demo data in every office: the East Zone (ZO-E > RO-WB > KL001 > DP-WB01), the South Zone filled out (RO-TN > CH001 > DP-TN01), 30 employees in every office that had none (FND, PAN/CPF missing, setup pending, no bank among them), Maker / Checker for KL001 and CH001, zone checkers for East and South |
| V025_x … V032_x | Modules 01–06: each script's header names its screens, tables, settings and sources (plans in `docs/plan/`) |
| V033_1 | Menu groups in the order of the payroll cycle (SRS v0.5 Screen Designs; HLD v0.6 six navigation groups): `app_screen.module_name` holds the group (Masters & Setup, Provisions & Inputs, Calculation, Outputs, Self-Service, Control & Governance) by SRS screen-code letter |
| V033_2 | A-07 setting `ESEVA_SESSION_IDLE_TIMEOUT` = 10 minutes (SRS-SEC-14: eSeva members idle out after 10 minutes, payroll users after 15) |
| V033_3 | Approval routes of M-04, M-22, M-23 and M-24 as the SRS states them: route version 2 with checker PAYROLL_CHECKER at HQ, and maker FINANCE for M-24 (SRS-G-7); grants moved; version 1 ends the day before |
| demo V033_9 | `finance.hq@` (FINANCE at HQ001), the M-24 maker of the demo |

## Rollback

Flyway Community does not run undo scripts, so rollbacks are applied by a DBA, newest first:

1. stop `fci-tomcat@batch` and `fci-tomcat@online`;
2. run `rollback/<vendor>/U<version>__*.sql` for every version above the target, newest first;
3. delete those versions' rows from `flyway_schema_history`;
4. deploy the release that matches the target version (`scripts/install-release.sh --rollback`).

The structural rollbacks are generated from the migrations by `scripts/db/gen_rollback.py`
(review the output); `U009` to `U024_9` are written by hand. `U014_2` → `U014_1` → `U014`
restore `approval_authority` from the workflow master and the V005 `otp_challenge` / `app_session`
shapes; the dropped zone number bands come back empty (they now come from HRMS). Data rollbacks (`U006`,
`U008_1`) delete every row of the seeded tables and never touch `audit_event`. `U018_2` brings the
interim result tables back empty and stops while PAYROLL_COMPUTATION job requests exist (job history is
never deleted by a rollback); run `U018_9` → `U018_2` → `U018_1` → `U018`.

## Rules

* Never edit a migration that has been applied to a persistent database; add a new version.
  (Until the Oracle cut-over every environment runs the in-memory H2 database, rebuilt at start.)
* Every new `V` script ships with its `U` script, for both vendors, in the same change.
