# End-to-end tests (Playwright)

Browser tests against the deployed local stack (NGINX → Next.js SSR → online and batch Tomcat, demo seed).

```powershell
powershell -File scripts\local\deploy-local.ps1          # fresh in-memory database, demo seed
cd frontend
$env:E2E_BASE_URL = 'https://localhost:8443'; $env:E2E_IGNORE_TLS_ERRORS = '1'
npx playwright test                                      # 111 tests (csp passes only behind the production build)
```

`E2E_IGNORE_TLS_ERRORS=1` only for the self-signed local certificate, never against UAT or production.

## Layout

One folder per business module, one spec per functionality. Folders and files are numbered where
the order matters: one worker runs them in path order, and the Module 01 specs need the cycle that
`05-payroll-processing/03-payroll-cycle` locks. Shared helpers are in `support/` (`app.ts`: sign-in, scopes,
labels, OTPs, forged API calls; `payroll-support.ts`: Module 01 fixtures).

Run one module: `npx playwright test e2e/06-exceptions-and-adjustments`; one functionality:
`npx playwright test e2e/05-payroll-processing/03-payroll-cycle.spec.ts`.

### `01-sign-in-and-access/` - Sign-in and access

| Spec                     | What it proves                                                                                                                                                                                                                                                                                                                                                                                                                 |
| ------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `sign-in`                | wrong password, unknown user, locked, deactivated, expired assignment; no page without a session; no password on the sign-in page                                                                                                                                                                                                                                                                                              |
| `personas-and-menus`     | each kind of user lands on its screen, sees only its menu and its office's data; a two-role user switches without signing out                                                                                                                                                                                                                                                                                                  |
| `access-control`         | an edited `?s=` never opens a screen (deny by default, also `../`, NUL and unknown codes); forged API calls refused; switching office or zone hides the previous one's data (W-01, M-01, A-05 audit trail; HQ keeps the national trail); the same person as maker and checker cannot decide its own item; sign-out ends page and API access; eSeva sees only its own record                                                    |
| `office-scope-switching` | changing office, region or zone keeps the open screen and reloads it for the new office: no row of the previous office after the switch, an open dossier and a deep-linked EIS do not survive, a screen the new role may not open gives way to its landing screen; each level (HQ, zone, region, DO) shows exactly its subtree; East and South offices of their own; edge-case employees (FND, PAN/CPF missing, setup pending) |

### `02-platform/` - Platform (security headers, phone layout, form controls)

| Spec                      | What it proves                                                                                                                                                                                              |
| ------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `content-security-policy` | no CSP violation, no inline style (except Next.js's route announcer), no third-party request                                                                                                                |
| `phone-layout`            | phone (390 px, touch): no sideways scroll, top bar and drawer controls reachable, search, no input that makes iOS zoom, controls at least 36 px                                                             |
| `form-controls`           | long free text is the shared text area (textarea, column maxlength, count, plain-text hint); payroll months are month pickers on the IST month; financial years are selects; DDL lengths (A-02, A-03, A-05) |

### `03-masters/` - Masters

| Spec              | What it proves                                                                                                                                                                                                                                                              |
| ----------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `employee-master` | employee data never cached (`no-store`, fetched on every visit); M-01 filters from the M-16 lists, search, masked PAN / bank, another office out of scope; I-17: a future suspension and an unfit revocation refused, a hold approved by the checker, a maker cannot record |
| `reference-lists` | M-16 only for the HQ System Administrator; a system list takes no new code; a relabel waits for the HQ checker, a second change waits; the approved label reaches another user's dropdown at once (cache); restored afterwards                                              |

### `04-approvals/` - Approvals

| Spec            | What it proves                                                                                                                                                                                                                                                                         |
| --------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `maker-checker` | an M-01 change reaches the record only after the checker; an access assignment needs the administrator's and the checker's OTP; approved with a future from-date it is not in force yet; revoked with a reason it stays on record without actions (own from-date per run: re-runnable) |

### `05-payroll-processing/` - Payroll processing (run in this order)

**Offices (OD-141, owner 2026-11-09).** The specs that initiate, freeze, compute, verify or lock a cycle, and those that need
a month of it open or locked (I-19, I-20, I-23, X-07), run in the cycle test office **DT001** "Delhi Test Office (E2E only)"
(seed `db/seed/V082_20`, demo profile) with its own users `maker.e2e`, `ddo.e2e`, `hr.e2e`, `finance.e2e`, `cpf.unit.e2e`
(`@fci-demo.test`) and its members, each a copy of an ACTIVE DH001 member with the EIS first digit set to 9
(`support/cycle-office.ts`: `TEST_OFFICE`, `testEis`). DO Delhi HQ (**DH001**) and Chennai (**CH001**) hold locked demo
months up to the month before the current one (V082_22); DH001's current month is an open, computed demo run with real
conditions (V082_22_1) that the read-only DH001 specs (C-02 pay rules, C-08, D-01) read, and CH001's current month is free.
The month-end lock spec (`16-month-end-lock`) runs in a second office, **DT002** "Delhi Test Office 2 (E2E only)" (seed
`V082_27`; users `maker.e2e2`, `ddo.e2e2`, ...; members 6xxxxx; `LOCK_OFFICE`). Cycle lease and return / un-freeze still
run at CH001 (they need a regional Checker above the unit) and the spare-unit freeze of `otp-gated-actions` at KL001.

| Spec                | What it proves                                                                                                                                                                                                                                                                                                              |
| ------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `01-payroll-period` | M-10 period selector: current (calendar month containing today) and every back-dated period offered with its cycle state, none in the future, newest first; choosing a back-dated period moves C-02 and survives a reload; future / unknown / malformed periods refused by the API; the choice is per session; Hindi labels |
| `02-batch-jobs`     | C-13 form validation (missing reason, period after the current one), submission, the maker cannot approve, the duplicate guard, the checker's approval and the batch instance running the job to COMPLETED, a forged unit refused                                                                                           |
| `03-payroll-cycle`  | C-02: initiate, freeze with OTP, the computation only after the checker approves, verify by the checker (never the maker), lock requested and approved with OTPs, then no action left (forward only)                                                                                                                        |

### `06-exceptions-and-adjustments/` - Exceptions and adjustments (Module 01; after the locked cycle)

| Spec                       | What it proves                                                                                                                                                                                                                                                                                                                                                                         |
| -------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `01-off-cycle-salary`      | I-20: the amount computed, never typed; no submission without the approval note; the Checker approves in W-01 and the month is excluded from the regular run; a second case for the month blocked by the duplicate check; cancel with reason; a locked salary month refused naming I-23; unknown member and non-ASCII reference refused; Checker read-only, administrator without I-20 |
| `02-supplementary-payment` | I-23: an open period refused (belongs in its regular run); a same-day sanction refused before sending; a pending salary of the locked period recorded with its order in one step, found already paid by the regular run and blocked, the order downloadable; cancel with reason; Checker read-only, administrator without I-23                                                         |
| `03-exceptions-worklist`   | X-05: the four tiles, filters sent to the worklist and to the CSV export (a `text/csv` attachment), no worklist for the administrator or an eSeva member (menu and API), every Module 01 screen in Hindi with no raw label key                                                                                                                                                         |
| `04-payroll-queries`       | X-06 against a Payroll ID of DH001's newest locked month: raise on a computed amount, the Maker answers but cannot confirm, the Checker confirms and it closes; a plain query closed by the answering Maker; status filter; unknown Payroll ID and Hindi text refused; an eSeva member raises on the own record only, never another's, and never answers                               |
| `05-hrms-fallback-upload`  | X-07: only HQ enables; enablement approved by the HQ Checker in W-01; a file not in the export layout rejected whole; each record accepted or rejected with its reason; the unit Checker approves with OTP; fallback (b) only with its order copy, an unsubmitted upload cancelled; an enablement withdrawn with a reason                                                              |

## Suites

`--project edge` stays the safe default: every spec, one worker, path order. Suites are the fast and the targeted way on one
stack, up to 8 workers, one per physical core (owner 2026-11-07), as `e2e/suites.json` plans them:

```powershell
powershell -File scripts\local\deploy-local.ps1 -OtpRequired Y -E2EParallelUsers   # the demo seed plus the worker clones
cd frontend
npm run e2e:affected                        # what the working tree changes, with related suites
npm run e2e:affected -- --base HEAD~3       # or against a base; or -- --files a.ts,b.ts
npm run e2e:failed                          # the last run's failed specs, with related suites
npm run e2e:suite -- tax cycle-serial       # by name, with what they depend on
npm run e2e:domain -- cpf                   # every suite with a spec of that domain
npm run e2e:lanes                           # every spec (e2e/0[1-8]*, menus 1-20); e2e:functional is the same
```

The owner's run order: fixed tests with related (`e2e:affected`), all previously failed with related (`e2e:failed`), then
the full suite (`e2e:lanes`). `--dry-run` prints the plan and starts nothing; `E2E_WORKERS=4` uses fewer
workers; other arguments go on to Playwright. The wireframe parity specs (`e2e/09-wireframe-parity/`) were removed on the
owner's decision of 2026-11-09 (OD-157); functional specs that assert a drawn object for layout stay.

**The manifest.** `npm run e2e:manifest` generates `e2e/suites.json` and the tables below from the sources:
per spec its menus (`app_menu_item`), screens, frontend modules (the screen registry, with the modules they import), backend
packages and endpoints (the modules' API calls, the controllers, `package-info` dependencies), users, helpers, shared state,
domains; per suite its lane, order and what it writes; the impact map. `e2e/suite-rules.mjs` holds the only judgement: areas,
reasons no signal shows, domains, shared packages. `npm run e2e:manifest:check` fails when the file is stale, a spec is in
no suite or in two, a spec has no domain, a fixture writer is in the parallel lane, a login has no clone, or a wave
holds two suites that share state. `e2e/suite-timings.json` holds measured seconds: `npm run e2e:manifest -- --timings` takes
the last run's. A `~` in the table is an estimate, the mean of a measured test.

**Who runs with whom.**

- **Parallel suites** (`suite-<name>`): each serial in itself, suites together in waves. A wave never holds two suites where
  one writes a key the other uses (`screen:M-18`, `cycle:DH001`): the rule of `scripts/e2e-plan.mjs`. W-01 and W-02 are not keys:
  specs find their row by a text stamped per run, and exact counts on a shared list make a spec serial.
- **Serial suites** run last, alone, one at a time, each after what it depends on: payroll cycles, database fixtures,
  settings and routes, sign-in and session rules, OTP limits, the signed-in login asserted as text. A spec with such a
  signal goes there by itself. A new spec no area names fails the check until it has one. A spec that passes alone and
  fails in a wave moves out, or its cause is fixed.
- **Menus 11-20.** Their specs sit in the suites of their areas like any other (OD-136, 2026-11-08, ends the OD-129
  hold).
- **Logins.** A spec's users are the `@fci-demo.test` logins in its source and in the helpers it imports
  (`MAKER`, `CHECKER`, the A-05 auditor of `payroll-support.ts`). The check fails on a parallel login with no
  clone and on a clone no parallel suite uses: an extra clone is one more row in every user list a serial spec
  reads. A new login of a parallel spec goes into the `IN` list of `db/e2e-parallel/h2/V990_1`, nothing else.

**No two workers are the same user**, because every demo user holds one session (`support/parallel-users.ts`). In a parallel
step worker n > 0 signs in as its clone, `maker.delhi.wn@fci-demo.test` (profile `e2e-parallel`, `db/e2e-parallel/h2`, w1 to w7:
hence at most 8 workers); worker 0 is the plain user; specs keep writing the plain login. A screen
also shows what its own user raised or acted on (`submitted_by`, `acted_by`), which a clone has not, so a clone sees other
rows: a spec asserting such rows is serial. The clones copy only assignments whose approval is
APPROVED: an approval names one assignment, and a copy of a pending one fails the Approvals Inbox. They never reach the plain
demo seed, a UAT or production: `StartupGuards` refuse the profile there and the release WAR leaves the scripts out. Each
worker reads its own OTP: the log shows a mobile's last four digits; a clone's end in `8<worker>7`.

**Impact map.** A changed path takes the longest prefix in the map. Shared kit, helpers, migrations, i18n bundles and the
platform map to every suite that uses them; a helper to the specs that import it, directly or not; a seed to the specs that
name its data; a path the map does not know, or a module no spec reaches, to all suites: the safe default is more suites.
Paths no test depends on (docs, unit tests) map to none. `affected` adds what the suites depend on and what depends on them.

A stack is one run: the serial lane locks the cycle. Each step first runs its lane's setup (`support/lanes.setup.ts`, needs
the E2E_DB_* fixture): the serial lane unlinks the clones from their employees, so that an Employee ID sign-in finds one
account, and the parallel lane links them again, so `e2e:failed` may re-run a parallel suite after the serial lane. What the
serial lane changed (the locked cycle) stays: for a clean full run, redeploy. Output: `test-results/lanes/<project>`.

<!-- suites:start -->
<!-- prettier-ignore-start -->

| Suite | Lane | Specs | Tests | Estimated | Depends on |
| --- | --- | --- | --- | --- | --- |
| `access` | parallel | 3 | 21 | 9 min | - |
| `arrears` | parallel | 8 | 33 | 7 min | - |
| `cpf-loans` | parallel | 2 | 4 | 54 s | - |
| `employee` | parallel | 5 | 26 | 12 min | - |
| `eseva` | parallel | 1 | 10 | 71 s | - |
| `labour` | parallel | 3 | 19 | 7 min | - |
| `masters` | parallel | 4 | 33 | 9 min | - |
| `platform` | parallel | 5 | 19 | 6 min | - |
| `rates` | parallel | 4 | 18 | 7 min | - |
| `reports` | parallel | 1 | 5 | 3 min | - |
| `tax` | parallel | 4 | 18 | 4 min | - |
| `approvals-serial` | serial | 6 | 22 | ~6 min | - |
| `arrears-serial` | serial | 2 | 8 | ~2 min | - |
| `audit-serial` | serial | 1 | 10 | ~3 min | - |
| `cpf-loans-serial` | serial | 3 | 25 | ~6 min | - |
| `cycle-serial` | serial | 18 | 65 | ~16 min | - |
| `employee-serial` | serial | 1 | 2 | ~30 s | - |
| `exceptions-serial` | serial | 8 | 49 | ~12 min | cycle-serial |
| `hrms-serial` | serial | 7 | 36 | ~9 min | cycle-serial |
| `labour-serial` | serial | 5 | 24 | ~6 min | - |
| `masters-serial` | serial | 2 | 13 | ~2 min | - |
| `pay-items-serial` | serial | 3 | 12 | ~3 min | - |
| `platform-serial` | serial | 3 | 16 | ~4 min | - |
| `rates-serial` | serial | 1 | 10 | 2 min | - |
| `sign-in-serial` | serial | 12 | 77 | ~19 min | - |

| Domain | Specs | Tests | Suites |
| --- | --- | --- | --- |
| `authentication-otp` | 17 | 100 | 4 |
| `rbac-data-scope` | 17 | 114 | 6 |
| `security-tampering` | 5 | 23 | 3 |
| `maker-checker` | 70 | 380 | 24 |
| `employee-hrms` | 34 | 205 | 14 |
| `payroll-cycle` | 32 | 159 | 9 |
| `labour` | 9 | 49 | 3 |
| `cpf` | 11 | 84 | 8 |
| `income-tax` | 10 | 57 | 6 |
| `nps` | 4 | 24 | 3 |
| `loans-advances` | 8 | 63 | 7 |
| `arrears-retro` | 11 | 56 | 3 |
| `off-cycle-supplementary` | 8 | 63 | 5 |
| `masters-rates` | 28 | 174 | 10 |
| `reference-data` | 6 | 38 | 4 |
| `audit-history` | 7 | 54 | 5 |
| `reports` | 5 | 36 | 5 |
| `failure-paths` | 7 | 25 | 4 |
| `i18n-responsive` | 5 | 24 | 3 |
| `pay-items` | 24 | 159 | 12 |

<!-- prettier-ignore-end -->
<!-- suites:end -->

## Rules of the suite

- **Module 01 specs (`06-exceptions-and-adjustments/`) run after `05-payroll-processing/03-payroll-cycle`** (one worker, file order): the locked run of record of the current
  period is the Payroll ID source of X-06 and the locked period of I-20 / I-23; run alone, those checks skip with the reason.
  Each spec cancels what it creates (and what an interrupted earlier run left), so it re-runs on the same stack.
- **One fresh database per test family.** Playwright and the API suites (`scripts/*-e2e.sh`, the
  scratch API suites) both change shared demo data; the C-02 cycle of the current period ends LOCKED
  and is never reopened (by design). Deploy, run Playwright; deploy again, run the API suites.
  `03-payroll-cycle.spec.ts` resumes a half-finished cycle and skips when the cycle is already locked.
- **Sessions are reused.** The sign-in API allows 10 sign-ins per IP and 5 per user per minute
  (ADR-0008): `workAs` signs each user in once per run and reuses the session cookies; a closed session
  (sign-out, single-session rule) falls back to a fresh sign-in; if the limiter still answers, `signIn`
  waits out the window and retries. Never raise the limits for tests.
- **OTPs** come from the online log (`devCode=`, demo profile only); a code is never used twice in a run.
- Every changed value carries the run's timestamp, and the M-16 relabel is restored, so the suite
  re-runs on the same stack (except the locked cycle above).

## Critical gate before a commit and push (owner 2026-11-09, OD-159)

A commit and push happens only after the critical end-to-end set passes on a freshly deployed stack (OTP
required): `npm run e2e:critical` (one worker, project `edge`; set the usual `E2E_*` variables). It covers
sign-in and access (sign-in, login OTP, access control), W-01 maker-checker, the payroll cycle (C-08 exceptions,
initiate to verify in DT001, the month-end lock in DT002 through G0-G6 and the OTP), the CPF sanction chain, tax
statutory outputs, HRMS change approval, My Payslips, final settlement and the PDF / ZIP downloads. The cycle and
lock specs lock a month, so the gate needs a fresh deployment each time. The full functional suite runs after
the push.
