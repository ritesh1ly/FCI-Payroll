#!/usr/bin/env node
/**
 * Per-module load and latency profile against a deployed local stack (owner 2026-10-25: profile
 * every module before it is closed; SRS-NF-6 target: a 50,000-user surge within 3 s).
 *
 * Best practice it follows: real sign-ins (one session per virtual user, distinct demo users of
 * the module's hierarchy seed), a warm-up phase that is not measured, a fixed request mix per
 * module, concurrency as a parameter, latency percentiles (p50 / p95 / p99 / max), error rate and
 * payload size per endpoint, and a JSON + Markdown report kept beside the module's numbers so a
 * later run compares against it. It never writes data: only GET endpoints are driven.
 *
 * Usage (stack B; the self-signed certificate of a local stack is accepted only for localhost):
 *   node scripts/perf/load-module.mjs --module 08 --base https://localhost:8743 \
 *        --concurrency 8 --iterations 40 --log scripts/local/runtime-b/logs/online/api.json
 */
import { closeSync, existsSync, mkdirSync, openSync, readFileSync, readSync, statSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const args = Object.fromEntries(
  process.argv.slice(2).reduce((pairs, a, i, all) => (a.startsWith('--') ? [...pairs, [a.slice(2), all[i + 1]]] : pairs), []),
);
const BASE = args.base ?? 'https://localhost:8743';
const MODULE = args.module ?? '08';
const CONCURRENCY = Number(args.concurrency ?? 8);
const ITERATIONS = Number(args.iterations ?? 40);
const WARMUP = Number(args.warmup ?? 5);
// Pause of each virtual user between its requests (ms): every virtual user shares one client
// address, so without it the run meets NGINX's per-address flood limit (fci_api) and not the app.
const THINK_MS = Number(args.think ?? 0);
const API_LOG = args.log ?? resolve(ROOT, 'scripts/local/runtime-b/logs/online/api.json');
const PASSWORD = 'Fci@Demo2026';
if (new URL(BASE).hostname === 'localhost') process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';

/** Financial year of a day (April to March) as fin_year holds it. */
function finYear(d = new Date()) {
  const ist = new Date(d.getTime() + 5.5 * 3600_000);
  const start = ist.getUTCMonth() >= 3 ? ist.getUTCFullYear() : ist.getUTCFullYear() - 1;
  return `${start}-${String((start + 1) % 100).padStart(2, '0')}`;
}
const FY = finYear();
const PREV_FY = `${Number(FY.slice(0, 4)) - 1}-${FY.slice(2, 4)}`;

/** The request mix of each module: per virtual user, the paths it reads (GET only). */
const MODULES = {
  '08': {
    role: 'PAYROLL_MAKER',
    users: [
      // tax desk, EIS of a member of its office, the office it acts at (V039_11_9 / V039_9): a
      // sign-in without one resumes the user's last office, which another run may have changed. M-18 is the Rule
      // Administrator's screen, not the desk's: it is not in this mix.
      ['tax.maker.delhi@fci-demo.test', '870001', 'DH001'],
      ['tax.maker.mumbai@fci-demo.test', '870014', 'MR001'],
      ['tax.maker.chennai@fci-demo.test', '870018', 'CH001'],
      ['tax.maker.kolkata@fci-demo.test', '870004', 'KL001'],
      ['tax.maker.ludhiana@fci-demo.test', '870025', 'LD001'],
      ['tax.maker.ro.delhi@fci-demo.test', '870012', 'RO-DL'],
      ['tax.maker.north@fci-demo.test', '870011', 'NA11'],
      // tax.maker.hq@ (HQ001): its sign-in at HQ001 is refused (PENDING 08-hq-scope), left out.
    ],
    paths: (eis) => [
      ['I-07 register', `/v1/tax/declarations?finYear=${FY}&page=0&size=20`],
      ['I-07 register p100', `/v1/tax/declarations?finYear=${PREV_FY}&page=0&size=100`],
      ['I-18 register', `/v1/tax/savings?finYear=${FY}&page=0&size=20`],
      ['I-18 member', `/v1/tax/savings/members/${eis}`],
      ['C-07 forecast', `/v1/tax/forecasts/${eis}?finYear=${FY}`],
      ['M-17 catalogue', '/v1/tax/output-definitions'],
      ['C-10 register', `/v1/tax/form16-certificates?finYear=${PREV_FY}&page=0&size=20`],
      ['C-11 returns', `/v1/tax/returns?finYear=${FY}&page=0&size=20`],
      ['C-11 challans', `/v1/tax/challans?finYear=${FY}`],
    ],
  },
  // Module 10 (I-03 claims, I-16 cafeteria): the Payroll Maker's Claim Register (as a page of 20 and
  // of 100, narrowed to labour), the labour claim types, the employee claims waiting on I-21, a
  // member's Medical Claim view and the I-16 selections.
  '10': {
    role: 'PAYROLL_MAKER',
    users: [
      ['maker.delhi@fci-demo.test', '870001', 'DH001'],
      ['tax.maker.chennai@fci-demo.test', '870018', 'CH001'],
      ['tax.maker.kolkata@fci-demo.test', '870004', 'KL001'],
      ['tax.maker.ludhiana@fci-demo.test', '870025', 'LD001'],
      ['tax.maker.ro.delhi@fci-demo.test', '870012', 'RO-DL'],
      ['tax.maker.north@fci-demo.test', '870011', 'NA11'],
    ],
    paths: (eis) => [
      ['I-03 register', '/v1/claims?page=0&size=20'],
      ['I-03 register p100', '/v1/claims?page=0&size=100'],
      ['I-03 labour claims', '/v1/claims?memberType=LABOUR&page=0&size=20'],
      ['I-03 claim types', '/v1/claims/types?memberType=LABOUR'],
      ['I-03 pending on I-21', '/v1/claims/pending-hrms?page=0&size=20'],
      ['I-03 medical view', `/v1/claims/medical?eis=${eis}`],
      ['I-16 selections', '/v1/cafeteria-selections?page=0&size=20'],
    ],
  },
  // Module 16 (staff payroll / I-21): the Payroll Maker's I-21 registers and the M-01 allowance
  // flags of a member of the office; the same desks as module 08 (PAYROLL_MAKER across the hierarchy).
  '16': {
    role: 'PAYROLL_MAKER',
    users: [
      ['tax.maker.delhi@fci-demo.test', '870001', 'DH001'],
      // tax.maker.mumbai@ (AG-C-PM-MR001) is granted the tax screens only, not I-21: left out.
      ['tax.maker.chennai@fci-demo.test', '870018', 'CH001'],
      ['tax.maker.kolkata@fci-demo.test', '870004', 'KL001'],
      ['tax.maker.ludhiana@fci-demo.test', '870025', 'LD001'],
      ['tax.maker.ro.delhi@fci-demo.test', '870012', 'RO-DL'],
      ['tax.maker.north@fci-demo.test', '870011', 'NA11'],
    ],
    paths: (eis) => [
      ['I-21 tasks', '/v1/hrms-change-tasks?page=0&size=20'],
      ['I-21 tasks p100', '/v1/hrms-change-tasks?page=0&size=100'],
      ['I-21 counts', '/v1/hrms-change-tasks/counts'],
      ['I-21 name/DOB register', '/v1/hrms-change-tasks?nameDob=true&page=0&size=20'],
      ['I-21 update requests', '/v1/hrms-update-requests?page=0&size=20'],
      ['I-21 request fields', '/v1/hrms-update-requests/fields'],
      ['M-01 flags', `/v1/employees/${eis}/allowance-flags`],
      ['M-01 flag history', `/v1/employees/${eis}/allowance-flags/history?page=0&size=20`],
    ],
  },
  // Module 18 (I-14 NPS deduction from HRMS): the month's register, its counts and the month before
  // (the demo records), as Finance reads it; the same desks hold I-14 VIEW as Payroll Makers.
  '18': {
    role: 'PAYROLL_MAKER',
    users: [
      ['tax.maker.delhi@fci-demo.test', '870001', 'DH001'],
      ['tax.maker.chennai@fci-demo.test', '870018', 'CH001'],
      ['tax.maker.kolkata@fci-demo.test', '870004', 'KL001'],
      ['tax.maker.ludhiana@fci-demo.test', '870025', 'LD001'],
      ['tax.maker.ro.delhi@fci-demo.test', '870012', 'RO-DL'],
      ['tax.maker.north@fci-demo.test', '870011', 'NA11'],
    ],
    paths: () => {
      const now = new Date(Date.now() + 5.5 * 3600_000);
      const ym = (d) => `${d.getUTCFullYear()}${String(d.getUTCMonth() + 1).padStart(2, '0')}`;
      const current = ym(now);
      const previous = ym(new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - 1, 1)));
      return [
        ['I-14 month', `/v1/nps-deductions?yymm=${current}&page=0&size=20`],
        ['I-14 previous p100', `/v1/nps-deductions?yymm=${previous}&page=0&size=100`],
        ['I-14 counts', `/v1/nps-deductions/counts?yymm=${previous}`],
        ['I-14 mismatches', `/v1/nps-deductions?yymm=${previous}&status=FLAG_WITHOUT_AMOUNT`],
      ];
    },
  },
};

// ---------------------------------------------------------------- deep pass (volume set)
// Owner 2026-10-27: deep profiling of the modules not profiled before (01-07, 09, 12) on the 50,000-employee VOLUME
// set (deploy-local.ps1 -Volume; V900_3 adds loans, penalty orders, X-05 exceptions and CPF ledgers at volume). The
// office desks are the 25 region Payroll Makers of the volume set (one region each, thousands of members); the
// Headquarters screens have one or two desk users, so their concurrency is what the seed gives.
const REGION_CODES = [
  'PB', 'UP', 'HR', 'MP', 'AP', 'TG', 'CG', 'OD', 'WB', 'BR', 'RJ', 'TN', 'MH', 'KA', 'KL', 'GJ', 'DL', 'JH', 'AS',
  'JK', 'UK', 'HP', 'NEF', 'AR', 'NM',
];
const VOLUME_MAKERS = REGION_CODES.map((c) => [`vol.maker.${c.toLowerCase()}@fci-demo.test`, null, `RO-${c}`]);
const ymOf = (monthsBack) => {
  const now = new Date(Date.now() + 5.5 * 3600_000);
  const d = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - monthsBack, 1));
  return { dash: `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, '0')}`, plain: `${d.getUTCFullYear()}${String(d.getUTCMonth() + 1).padStart(2, '0')}` };
};
/** The first row of a paged list (its id or EIS), for a detail path; null when the list is empty. */
async function first(session, path, field) {
  const r = await session.call('GET', path);
  if (r.status !== 200) return null;
  const body = await r.json().catch(() => null);
  return body?.items?.[0]?.[field] ?? null;
}

Object.assign(MODULES, {
  // 01 Platform general: X-05 exception worklist (5,000 volume entries), X-07 fallback, I-20 off-cycle,
  // I-23 supplementary, the payroll query desk; the region Payroll Makers.
  '01': {
    role: 'PAYROLL_MAKER',
    users: VOLUME_MAKERS,
    paths: () => [
      ['X-05 worklist', '/v1/exceptions?page=0&size=20'],
      ['X-05 worklist p100', '/v1/exceptions?page=0&size=100'],
      ['X-05 blocking open', '/v1/exceptions?status=OPEN&severity=BLOCKING&page=0&size=20'],
      ['X-07 summary', '/v1/hrms-fallback/summary'],
      ['X-07 uploads', '/v1/hrms-fallback/uploads?page=0&size=20'],
      ['I-20 off-cycle', '/v1/off-cycle?page=0&size=20'],
      ['I-23 tiles', '/v1/supplementary/tiles'],
      ['I-23 supplementary', '/v1/supplementary?page=0&size=20'],
      ['queries', '/v1/queries?page=0&size=20'],
      ['query counts', '/v1/queries/counts'],
    ],
  },
  // 02 Security, RBAC, approvals: the W-01 inbox and submissions of the region checkers and makers (alternating),
  // plus the session's own menu, actions and scopes, read on every screen.
  '02': {
    role: null,
    users: REGION_CODES.map((c, i) =>
      i % 2 === 0
        ? [`vol.checker.${c.toLowerCase()}@fci-demo.test`, null, `RO-${c}`, 'PAYROLL_CHECKER']
        : [`vol.maker.${c.toLowerCase()}@fci-demo.test`, null, `RO-${c}`, 'PAYROLL_MAKER'],
    ),
    // W-01 (the inbox, OTP step-up once per session) is the checkers'; W-02 (own submissions) the makers'.
    prepare: async (s) => ({ checker: s.login.includes('.checker.') }),
    paths: (_eis, ctx) => [
      ctx.checker ? ['W-01 inbox', '/v1/inbox?page=0&size=20'] : ['W-02 submissions', '/v1/submissions?page=0&size=20'],
      ['menu', '/menu'],
      ['me', '/auth/me'],
      ['scopes', '/auth/scopes'],
      ['actions', '/v1/me/actions'],
    ],
  },
  // 02 admin screens (A-01 users with the 50 volume desk users added, active users, roles, groups, assignments,
  // readiness, workflow master), by the System Administrator (one desk user).
  '02a': {
    role: 'SYSTEM_ADMINISTRATOR',
    users: [['sysadmin@fci-demo.test', null, 'HQ001']],
    paths: () => [
      ['A-01 users', '/v1/admin/users?page=0&size=20'],
      ['A-01 users p100', '/v1/admin/users?page=0&size=100'],
      ['A-01 user search', '/v1/admin/users?q=vol.maker&page=0&size=20'],
      ['A-04 active users', '/v1/admin/active-users?page=0&size=20'],
      ['A-02 roles', '/v1/admin/roles'],
      ['A-03 groups', '/v1/admin/groups'],
      ['A-03 assignments', '/v1/admin/assignments'],
      ['readiness', '/v1/admin/readiness'],
      ['A-08 workflow actions', '/v1/admin/workflow-actions'],
      ['A-08 unstaffed', '/v1/admin/workflow-actions/unstaffed'],
      ['M-16 reference lists', '/v1/masters/reference-lists'],
      ['M-26 labels', '/v1/masters/labels'],
      ['M-26 missing Hindi', '/v1/masters/labels/missing-hindi'],
      ['X-04 search 7 days', `/v1/admin/audit?from=${new Date(Date.now() - 7 * 86400_000).toISOString()}&to=${new Date().toISOString()}&page=0&size=20`],
    ],
  },
  // 03 Audit trail: X-04 search over the last 30 days and the record history of an employee, by the Super User.
  '03': {
    role: 'SUPER_USER',
    users: [['superuser.hq@fci-demo.test', null, 'HQ001']],
    prepare: async (s) => ({ eis: await first(s, '/v1/employees?q=V0&page=0&size=10', 'eis') }),
    paths: (_eis, ctx) => {
      const to = new Date().toISOString();
      const from = new Date(Date.now() - 30 * 86400_000).toISOString();
      return [
        ['X-04 search 30 days', `/v1/admin/audit?from=${from}&to=${to}&page=0&size=20`],
        ['X-04 search p100', `/v1/admin/audit?from=${from}&to=${to}&page=0&size=100`],
        ['X-04 by entity', `/v1/admin/audit?from=${from}&to=${to}&entity=employee&page=0&size=20`],
        ['record types', '/v1/audit/record-types'],
        ['history employee', `/v1/audit/history?entity=employee&key=${ctx.eis ?? 'V000001'}&page=0&size=20`],
      ];
    },
  },
  // 04 Masters / MDM and 06 rule engine: the Rule Administrator's catalogue screens at Headquarters.
  '04': {
    role: 'RULE_ADMINISTRATOR',
    users: [
      ['su.hq@fci-demo.test', null, 'HQ001'],
      ['tax.ruleadmin@fci-demo.test', null, 'HQ001'],
    ],
    paths: () => [
      ['M-22 staff rates', '/v1/masters/staff-rates'],
      ['M-23 other parameters', '/v1/masters/other-parameters'],
      ['M-24 GL mappings', '/v1/masters/gl-mappings'],
      ['M-14 net-pay floors', '/v1/masters/net-pay-floors'],
      ['M-15 ceiling norms', '/v1/masters/ceiling-norms'],
      ['M-25 circulars', '/v1/masters/circulars'],
      ['M-25 current', '/v1/masters/circulars/current'],
      ['M-27 operations', '/v1/masters/operations'],
      ['M-19 interest rates', '/v1/cpf/interest-rates'],
      ['reference lists', '/v1/reference/lists'],
    ],
  },
  '06': {
    role: 'RULE_ADMINISTRATOR',
    users: [
      ['su.hq@fci-demo.test', null, 'HQ001'],
      ['tax.ruleadmin@fci-demo.test', null, 'HQ001'],
    ],
    paths: () => [
      ['M-08 rules', '/v1/rules'],
      ['M-08 pending', '/v1/rules/pending'],
      ['M-08 vocabulary', '/v1/rules/vocabulary'],
      ['M-08 simulation offices', '/v1/rules/simulation-offices'],
      ['M-07 components', '/v1/pay-components'],
      ['M-07 usages 101', '/v1/pay-components/101/usages'],
    ],
  },
  // 05 Payroll cycle control: the region Payroll Makers' cycle desk (C-01 status over the region's units, the
  // period, the cycle register, simulations, MDP, calendars and pre-run checks of last month).
  '05': {
    role: 'PAYROLL_MAKER',
    users: VOLUME_MAKERS,
    paths: () => {
      const last = ymOf(1).dash;
      return [
        ['C-01 status', '/v1/cycle-status'],
        ['C-01 units', '/v1/cycle-status/units?page=0&size=20'],
        ['periods', '/v1/periods'],
        ['cycle register', '/v1/cycle-register-entries?page=0&size=20'],
        ['cycle last month', `/v1/cycles/${last}`],
        ['pre-run checks', `/v1/cycles/${last}/pre-run-checks`],
        ['C-09 simulations', '/v1/simulations?page=0&size=20'],
        ['C-09 tiles', '/v1/simulations/tiles'],
        ['MDP', '/v1/mdp-transactions?page=0&size=20'],
        ['calendars', '/v1/payroll-calendars'],
        ['run order', '/v1/run-order-steps'],
        ['M-01 members', '/v1/employees?page=0&size=20'],
        ['M-01 counts', '/v1/employees/counts'],
      ];
    },
  },
  // 07 CPF: the Manager (CPF) of the North zone (7,600 volume staff with a ledger each, six months of rows).
  '07': {
    role: 'CPF_MANAGER',
    users: [['cpf.manager.north@fci-demo.test', null, 'NA11']],
    // A member of the zone with a ledger: the first of the zone's C-12 report.
    prepare: async (s) => ({ eis: await first(s, `/v1/cpf/interest-runs/not-calculated?finYear=${FY}&page=0&size=10`, 'eis') }),
    paths: (_eis, ctx) => [
      ['I-12 sanctions', '/v1/cpf/sanctions?page=0&size=20'],
      ['I-09 deputation entries', '/v1/cpf/deputation-entries?page=0&size=20'],
      ['borrowing orgs', '/v1/cpf/borrowing-organisations'],
      ['I-22 corrections', '/v1/cpf/opening-balance-corrections?page=0&size=20'],
      ['opening balance', `/v1/cpf/opening-balances?eis=${ctx.eis ?? 'V000001'}&asAt=${new Date().toISOString().slice(0, 10)}`],
      ['C-12 runs', `/v1/cpf/interest-runs?finYear=${FY}`],
      ['C-12 not calculated', `/v1/cpf/interest-runs/not-calculated?finYear=${FY}&page=0&size=20`],
    ],
  },
  // 09 Loans: I-04 over 5,500 volume loans, by the region Payroll Makers.
  '09': {
    role: 'PAYROLL_MAKER',
    users: VOLUME_MAKERS,
    prepare: async (s) => ({ loan: await first(s, '/v1/loans?page=0&size=10', 'id') }),
    paths: (_eis, ctx) => [
      ['I-04 loans', '/v1/loans?page=0&size=20'],
      ['I-04 loans p100', '/v1/loans?page=0&size=100'],
      ['I-04 loan', `/v1/loans/${ctx.loan ?? 1}`],
      ['I-04 types', '/v1/loans/types'],
      ['I-04 requests', '/v1/loans/requests?page=0&size=20'],
    ],
  },
  // 12 Penalty: I-06 over 1,100 volume orders (6,600 schedule lines), by the region Payroll Makers.
  '12': {
    role: 'PAYROLL_MAKER',
    users: VOLUME_MAKERS,
    prepare: async (s) => ({ order: await first(s, '/v1/penalties?page=0&size=10', 'id') }),
    paths: (_eis, ctx) => [
      ['I-06 orders', '/v1/penalties?page=0&size=20'],
      ['I-06 orders p100', '/v1/penalties?page=0&size=100'],
      ['I-06 order', `/v1/penalties/${ctx.order ?? 1}`],
      ['I-06 requests', '/v1/penalties/requests?page=0&size=20'],
      ['I-06 over-and-above', '/v1/penalties/over-and-above?page=0&size=20'],
    ],
  },
});

// ---------------------------------------------------------------- after module 23 (owner 2026-10-28)
// Deep profiling of the modules built since the first deep pass: 11, 13, 14, 15, 17, 19, 20, 22, 23
// (21 DPS is not built). The region Payroll Makers of the volume set read the office screens; eSeva
// reads are the members' own (the volume eSeva accounts, perf-test S2 / S7).
Object.assign(MODULES, {
  // 11 Full & final settlement (O-05): the case register, the cases awaiting action and one case.
  '11': {
    role: 'PAYROLL_MAKER',
    users: VOLUME_MAKERS,
    prepare: async (s) => ({ kase: await first(s, '/v1/settlement-cases?page=0&size=10', 'id') }),
    paths: (_eis, ctx) => [
      ['O-05 cases', '/v1/settlement-cases?page=0&size=20'],
      ['O-05 cases p100', '/v1/settlement-cases?page=0&size=100'],
      ['O-05 awaiting', '/v1/settlement-cases/awaiting'],
      ...(ctx.kase ? [['O-05 case', `/v1/settlement-cases/${ctx.kase}`]] : []),
    ],
  },
  // 13 Arrears, retro, wage revision (I-08, I-24, I-25): the arrear events, the register and the revisions.
  '13': {
    role: 'PAYROLL_MAKER',
    users: VOLUME_MAKERS,
    prepare: async (s) => ({ event: await first(s, '/v1/arrear-events?page=0&size=10', 'id') }),
    paths: (_eis, ctx) => [
      ['I-08 events', '/v1/arrear-events?page=0&size=20'],
      ['I-08 events p100', '/v1/arrear-events?page=0&size=100'],
      ...(ctx.event ? [['I-08 details', `/v1/arrear-events/${ctx.event}/details?page=0&size=20`]] : []),
      ['I-08 register download', '/v1/arrear-events/register'],
      ['I-24 revisions', '/v1/wage-revisions?page=0&size=20'],
    ],
  },
  // 15 MIS and reporting (O-04, M-12): the catalogue and last month's component summary by unit and zone.
  '15': {
    role: 'PAYROLL_MAKER',
    users: VOLUME_MAKERS,
    paths: () => {
      const last = ymOf(1).plain;
      return [
        ['O-04 catalogue', '/v1/reports/catalogue'],
        ['O-04 summary unit', `/v1/reports/component-summary?yymm=${last}&level=UNIT`],
        ['O-04 summary zone', `/v1/reports/component-summary?yymm=${last}&level=ZONE`],
      ];
    },
  },
  // 17 Increment and pay fixation (I-05, M-03): the month's due list (with the stagnation test of
  // members at the maximum), the scales and a member's Basic history.
  '17': {
    role: 'PAYROLL_MAKER',
    users: VOLUME_MAKERS,
    prepare: async (s) => ({ eis: await first(s, `/v1/increments/due?month=${ymOf(0).dash}&page=0&size=10`, 'eis') }),
    paths: (eis) => [
      ['I-05 due', `/v1/increments/due?month=${ymOf(0).dash}&page=0&size=20`],
      ['I-05 due p100', `/v1/increments/due?month=${ymOf(0).dash}&page=0&size=100`],
      ['M-03 scales', '/v1/pay-scales?page=0&size=20'],
      ...(eis ? [['I-05 Basic history', `/v1/basic-history?eis=${eis}`]] : []),
    ],
  },
  // 19 Leave (M-13, M-20, I-11): leave types and events with their counts, the pay parameters.
  '19': {
    role: 'PAYROLL_MAKER',
    users: VOLUME_MAKERS,
    paths: () => [
      ['M-13 types', '/v1/leave-types?page=0&size=20'],
      ['M-13 counts', '/v1/leave-types/counts'],
      ['I-11 events', '/v1/leave-events?page=0&size=20'],
      ['I-11 events p100', '/v1/leave-events?page=0&size=100'],
      ['I-11 counts', '/v1/leave-events/counts'],
      ['M-20 parameters', '/v1/leave-pay-parameters'],
      ['leave encashment arrears', '/v1/leave-encashment-arrears?page=0&size=20'],
    ],
  },
  // 20 Labour common and departmental (M-02, M-06, M-09, I-01, I-02): members, gangs, a gang's
  // members, attendance and work slips of the month, the rate and norm sets.
  '20': {
    role: 'PAYROLL_MAKER',
    users: VOLUME_MAKERS,
    prepare: async (s) => ({ gang: await first(s, '/v1/labour/gangs?page=0&size=10', 'id') }),
    paths: (_eis, ctx) => [
      ['M-02 members', '/v1/labour/members?page=0&size=20'],
      ['M-02 members p100', '/v1/labour/members?page=0&size=100'],
      ['M-02 options', '/v1/labour/options'],
      ['M-06 gangs', '/v1/labour/gangs?page=0&size=20'],
      ...(ctx.gang
        ? [
            ['M-06 gang members', `/v1/labour/gangs/${ctx.gang}/members`],
            ['I-01 attendance', `/v1/labour/gangs/${ctx.gang}/attendance?month=${ymOf(0).dash}`],
            ['I-02 work slips', `/v1/labour/gangs/${ctx.gang}/work-slips?month=${ymOf(0).dash}`],
          ]
        : []),
      ['M-05 DPS rate sets', '/v1/labour/rate-sets?payrollType=DPS_LABOUR&page=0&size=20'],
      ['norm sets', '/v1/labour/norm-sets'],
    ],
  },
  // 22 NWNP labour (M-05): the rate sets and their options.
  '22': {
    role: 'PAYROLL_MAKER',
    users: VOLUME_MAKERS,
    paths: () => [
      ['M-05 rate sets', '/v1/labour/nwnp-rate-sets?page=0&size=20'],
      ['M-05 options', '/v1/labour/nwnp-rate-sets/options'],
    ],
  },
  // 23 Integrations (X-03, O-11): the HQ System Administrator's dashboard (one or two desks).
  '23': {
    role: 'SYSTEM_ADMINISTRATOR',
    users: [['sysadmin@fci-demo.test', null, 'HQ001']],
    paths: () => [
      ['X-03 summary', '/v1/integration/summary'],
      ['X-03 interfaces', '/v1/integration/interfaces?page=0&size=20'],
      ['X-03 windows', '/v1/integration/windows?page=0&size=20'],
      ['X-03 exceptions', '/v1/integration/exceptions?page=0&size=20'],
    ],
  },
});

// Imported by perf-test.mjs (the mixed performance test): the mixes and the sign-in are shared; the
// module run below starts only when this file is the script node was asked to run.
const RUN_AS_SCRIPT =
  Boolean(process.argv[1]) && resolve(process.argv[1]).toLowerCase() === fileURLToPath(import.meta.url).toLowerCase();
const mod = MODULES[MODULE] ?? MODULES['08'];
if (RUN_AS_SCRIPT && !MODULES[MODULE]) throw new Error(`no request mix for module ${MODULE}`);

/** A cookie jar per virtual user (the session and the CSRF token). */
class Session {
  /** {@code headers}: extra request headers, e.g. X-Forwarded-For of the office a virtual user stands for. */
  constructor(login, headers = {}) {
    this.login = login;
    this.cookies = new Map();
    this.headers = headers;
  }
  header() {
    return [...this.cookies].map(([k, v]) => `${k}=${v}`).join('; ');
  }
  keep(response) {
    for (const c of response.headers.getSetCookie?.() ?? []) {
      const [pair] = c.split(';');
      const at = pair.indexOf('=');
      this.cookies.set(pair.slice(0, at), pair.slice(at + 1));
    }
  }
  /** {@code extra}: headers of this request only (e.g. If-None-Match of a PDF download, OD-144). */
  async call(method, path, body, extra = {}) {
    const headers = { Accept: 'application/json', Cookie: this.header(), ...this.headers, ...extra };
    if (method !== 'GET') {
      headers['Content-Type'] = 'application/json';
      headers['X-XSRF-TOKEN'] = decodeURIComponent(this.cookies.get('XSRF-TOKEN') ?? '');
      headers['Idempotency-Key'] = crypto.randomUUID();
    }
    const r = await fetch(`${BASE}/api${path}`, { method, headers, body: body ? JSON.stringify(body) : undefined, redirect: 'manual' });
    this.keep(r);
    // A sensitive screen (SRS-G-48) asks once per session for an OTP step-up, as the browser does:
    // confirm it with the development code of the API log and send the request again.
    if (r.status === 403 && !this.steppedUp && path !== '/auth/step-up') {
      const problem = await r.clone().json().catch(() => ({}));
      if (problem.errorCode === 'AUTH-403-STEP-UP') {
        this.steppedUp = true;
        await this.stepUp();
        return this.call(method, path, body, extra);
      }
    }
    return r;
  }

  /** The OTP step-up of the session, answered with the code the development sender logged. */
  async stepUp() {
    const me = await (await this.call('GET', '/auth/me')).json().catch(() => ({}));
    const since = Date.now();
    const challenge = await this.call('POST', '/auth/step-up', {});
    const body = await challenge.json().catch(() => ({}));
    const id = Number(body?.args?.[0]);
    for (let attempt = 0; attempt < 10 && id; attempt++) {
      await new Promise((ok) => setTimeout(ok, 300));
      const code = stepUpCode(me.userId, since);
      if (code) {
        await this.call('POST', '/auth/step-up', { otp: { challengeId: id, code } });
        return;
      }
    }
  }
}

/** The newest step-up OTP of the user in the API log since {@code since} (dev code). */
function stepUpCode(userId, since) {
  if (!existsSync(API_LOG) || userId == null) return null;
  const lines = readFileSync(API_LOG, 'utf8')
    .split('\n')
    .filter((l) => l.includes('devCode=') && l.includes('"uri":"/api/auth/step-up"') && l.includes(`"userId":"${userId}"`));
  for (const line of lines.reverse()) {
    const at = /"@timestamp":"([^"]+)"/.exec(line)?.[1];
    const code = /devCode=(\d{6})/.exec(line)?.[1];
    if (code && at && Date.parse(at) >= since - 1000) return code;
  }
  return null;
}

/**
 * The sign-in OTP (ADR-0026) of one login request: the development sender logs the code with the
 * request's correlation id, which the load script chose (X-Correlation-Id), so concurrent sign-ins
 * never take each other's code. One shared reader follows the log (only the bytes appended since its
 * last read) and hands each code to the sign-in waiting for it: a surge of hundreds of sign-ins costs
 * the load generator one small read every 100 ms, not one 4 MB read per user per attempt, which would
 * be measured as sign-in time.
 */
const codeWaiters = new Map();
const codesSeen = new Map();
let logOffset = null;
let logTimer = null;
let logRest = '';

function readNewLogLines() {
  if (!existsSync(API_LOG)) return;
  const size = statSync(API_LOG).size;
  if (logOffset === null || size < logOffset) logOffset = Math.max(0, size - 4 * 1024 * 1024);
  if (size === logOffset) return;
  const fd = openSync(API_LOG, 'r');
  const buffer = Buffer.alloc(size - logOffset);
  readSync(fd, buffer, 0, buffer.length, logOffset);
  closeSync(fd);
  logOffset = size;
  const lines = (logRest + buffer.toString('utf8')).split('\n');
  logRest = lines.pop() ?? '';
  for (const line of lines) {
    if (!line.includes('purpose=AUTH_LOGIN')) continue;
    const id = /"correlationId":"([^"]+)"/.exec(line)?.[1];
    const code = /devCode=(\d{6})/.exec(line)?.[1];
    if (!id || !code) continue;
    const waiter = codeWaiters.get(id);
    if (waiter) {
      codeWaiters.delete(id);
      waiter(code);
    } else {
      codesSeen.set(id, code);
    }
  }
}

function loginCode(correlationId, timeoutMs = 15_000) {
  const known = codesSeen.get(correlationId);
  if (known) {
    codesSeen.delete(correlationId);
    return Promise.resolve(known);
  }
  if (!logTimer) {
    logTimer = setInterval(readNewLogLines, 100);
    logTimer.unref();
  }
  return new Promise((resolve) => {
    const timer = setTimeout(() => {
      codeWaiters.delete(correlationId);
      resolve(null);
    }, timeoutMs);
    codeWaiters.set(correlationId, (code) => {
      clearTimeout(timer);
      resolve(code);
    });
  });
}

async function signIn(login, office, role = mod.role, headers = {}) {
  const s = new Session(login, headers);
  await s.call('GET', '/auth/csrf');
  if (logOffset === null) readNewLogLines();
  const correlationId = crypto.randomUUID();
  s.headers = { ...headers, 'X-Correlation-Id': correlationId };
  let r = await s.call('POST', '/auth/login', { loginId: login, password: PASSWORD, role, location: office });
  s.headers = headers;
  if (r.status === 202) {
    // X-08 step 2: the code sent to the registered mobile (the API log's development code).
    const code = await loginCode(correlationId);
    if (!code) throw new Error(`${login}: no sign-in OTP in ${API_LOG}`);
    r = await s.call('POST', '/auth/login/otp', { code });
  }
  if (r.status !== 200) throw new Error(`${login}: sign-in ${r.status} ${await r.text()}`);
  const me = await (await s.call('GET', '/auth/me')).json();
  if (me.stepUpRequired) {
    s.steppedUp = true;
    await s.stepUp();
  }
  return s;
}

function percentile(sorted, p) {
  if (sorted.length === 0) return 0;
  return sorted[Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1)];
}

/**
 * --desks checkers: the region mixes as the 25 region Payroll Checkers of the volume set instead of
 * the Makers (the same offices; the Checker's view of the screens). Each user may receive 20 sign-in
 * codes an hour: alternating the two desk sets lets a deep pass of every module run in one sitting.
 */
function desks(users) {
  if (args.desks !== 'checkers') return users;
  return users.map(([login, eis, office, role]) =>
    login.startsWith('vol.maker.') ? [login.replace('vol.maker.', 'vol.checker.'), eis, office, 'PAYROLL_CHECKER'] : [login, eis, office, role],
  );
}

async function main() {
  const users = desks(mod.users).slice(0, CONCURRENCY);
  const sessions = [];
  for (const [login, eis, office, role] of users) {
    // The login limiter allows 10 per IP a minute: pace the sign-ins.
    const session = await signIn(login, office, role ?? mod.role);
    const ctx = mod.prepare ? await mod.prepare(session) : {};
    sessions.push({ session, eis: eis ?? ctx.eis ?? null, ctx });
    await new Promise((ok) => setTimeout(ok, 6_500));
  }
  const samples = new Map();
  const record = (name, ms, status, bytes) => {
    const s = samples.get(name) ?? { ms: [], errors: 0, statuses: {}, bytes: 0, n: 0 };
    s.ms.push(ms);
    s.n++;
    s.bytes += bytes;
    s.statuses[status] = (s.statuses[status] ?? 0) + 1;
    if (status >= 400) s.errors++;
    samples.set(name, s);
  };
  // The first failure of each user and endpoint: who, what, which status (never a response body).
  const failures = new Map();
  const run = async ({ session, eis, ctx }, measured) => {
    for (const [name, path] of mod.paths(eis, ctx ?? {})) {
      const t0 = performance.now();
      const r = await session.call('GET', path);
      const text = await r.text();
      if (measured) record(name, performance.now() - t0, r.status, text.length);
      const key = `${session.login} ${name}`;
      if (r.status >= 400 && !failures.has(key)) failures.set(key, r.status);
      if (THINK_MS > 0) await new Promise((ok) => setTimeout(ok, THINK_MS));
    }
  };
  for (let i = 0; i < WARMUP; i++) await Promise.all(sessions.map((u) => run(u, false)));
  const started = performance.now();
  for (let i = 0; i < ITERATIONS; i++) await Promise.all(sessions.map((u) => run(u, true)));
  const seconds = (performance.now() - started) / 1000;

  const rows = [...samples].map(([name, s]) => {
    const sorted = [...s.ms].sort((a, b) => a - b);
    return {
      endpoint: name,
      requests: s.n,
      errors: s.errors,
      statuses: s.statuses,
      p50: Math.round(percentile(sorted, 50)),
      p95: Math.round(percentile(sorted, 95)),
      p99: Math.round(percentile(sorted, 99)),
      max: Math.round(sorted[sorted.length - 1] ?? 0),
      avgBytes: Math.round(s.bytes / s.n),
    };
  });
  const total = rows.reduce((n, r) => n + r.requests, 0);
  const report = {
    module: MODULE,
    at: new Date().toISOString(),
    base: BASE,
    concurrency: sessions.length,
    iterations: ITERATIONS,
    seconds: Math.round(seconds * 10) / 10,
    throughput: Math.round(total / seconds),
    // Owner 2026-10-27: 3 s for an interactive request, 1 minute for a report / Excel / CSV download.
    slaMs: 3000,
    downloadSlaMs: 60_000,
    breaches: rows.filter((r) => r.p99 > (/download|report|export/i.test(r.endpoint) ? 60_000 : 3000)).map((r) => r.endpoint),
    failures: Object.fromEntries(failures),
    rows,
  };
  const out = resolve(ROOT, 'docs/perf');
  mkdirSync(out, { recursive: true });
  writeFileSync(resolve(out, `module-${MODULE}-load.json`), `${JSON.stringify(report, null, 2)}\n`);
  const table = [
    `| Endpoint | Requests | Errors | p50 ms | p95 ms | p99 ms | max ms | avg bytes |`,
    `|---|---|---|---|---|---|---|---|`,
    ...rows.map((r) => `| ${r.endpoint} | ${r.requests} | ${r.errors} | ${r.p50} | ${r.p95} | ${r.p99} | ${r.max} | ${r.avgBytes} |`),
  ].join('\n');
  console.log(`module ${MODULE}: ${sessions.length} users x ${ITERATIONS} iterations, ${total} requests in ${report.seconds}s (${report.throughput}/s)`);
  console.log(table);
  if (report.breaches.length) console.log(`p99 above the 3 s SLA: ${report.breaches.join(', ')}`);
}

export { MODULES, Session, signIn, percentile, BASE };

if (RUN_AS_SCRIPT) main().catch((e) => {
  console.error(e.message);
  process.exit(1);
});
