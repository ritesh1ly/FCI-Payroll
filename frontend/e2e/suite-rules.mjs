// The human judgement behind e2e/suites.json; everything else in that file is derived from the sources by
// scripts/e2e-manifest.mjs. Change a rule, then `npm run e2e:manifest`. See e2e/README.md "Suites".

/**
 * The areas: a menu or family of screens. A spec's path picks the first area that matches; a spec no
 * area names goes to `unclassified`, in the serial lane. Each area gives a parallel suite <area> and a
 * serial suite <area>-serial; a spec is serial for a derived reason or one in SERIAL below.
 * `serial: true` makes the whole area serial.
 */
export const AREAS = [
  {
    area: 'access',
    match: /01-sign-in-and-access\/(personas-and-menus|office-hierarchy|office-scope-switching)$/,
  },
  { area: 'sign-in', serial: true, match: /^01-sign-in-and-access\// },
  { area: 'employee', match: /02-platform\/list-paging|03-masters\/(employee-master|staff-transfers)/ },
  { area: 'employee', match: /08-employee-payroll\/(03-allowance-flags|05-nps-deductions|16-onboarding)/ },
  { area: 'platform', match: /^02-platform\// },
  { area: 'labour', match: /03-masters\/(dps-labour|labour|nwnp)/ },
  { area: 'approvals', serial: true, match: /^04-approvals\/|03-masters\/reference-lists/ },
  {
    area: 'masters',
    match: /03-masters\/(accounting-mapping|ceiling-norm-rules|component-catalogue|mdm-masters|rule-)/,
  },
  { area: 'rates', match: /^03-masters\/|08-employee-payroll\/19-g3-gap-fixes/ },
  { area: 'cycle', serial: true, match: /^05-payroll-processing\// },
  {
    area: 'hrms',
    serial: true,
    match: /06-exceptions-and-adjustments\/(05-|16-|17-|18-)|08-employee-payroll\/(01-|02-|21-)/,
  },
  { area: 'exceptions', serial: true, match: /06-exceptions-and-adjustments\/0[1-4]-|\/0[89]-/ },
  { area: 'tax', match: /06-exceptions-and-adjustments\/1[0-3]-/ },
  { area: 'cpf-loans', match: /06-exceptions-and-adjustments\/(06-|07-|14-)|08-employee-payroll\/(06-|23-)/ },
  { area: 'eseva', match: /06-exceptions-and-adjustments\/15-/ },
  { area: 'reports', match: /07-audit\/05-mis-reports/ },
  { area: 'audit', serial: true, match: /^07-audit\// },
  { area: 'arrears', match: /08-employee-payroll\/(1[0-5]|17|18|20|22)-/ },
  { area: 'pay-items', match: /^08-employee-payroll\// },
];

/** Why a spec cannot run beside others, where no signal in its source shows it. */
export const SERIAL = {
  '01-sign-in-and-access/access-control': 'locks and signs out accounts',
  '01-sign-in-and-access/login-best-practices': 'counts sign-in requests; rate limits',
  '01-sign-in-and-access/login-otp': 'OTP resend and limit rules',
  '01-sign-in-and-access/sign-in': 'locked and deactivated accounts',
  '01-sign-in-and-access/sign-in-methods': 'signs in by Employee ID and mobile: plain users only',
  '01-sign-in-and-access/step-up-and-session': 'session expiry and step-up',
  '01-sign-in-and-access/role-screen-matrix': 'every role in turn, session after session',
  '02-platform/failure-paths': 'OTP limits and forced failures',
  '02-platform/session-idle': 'session expiry',
  '03-masters/reference-lists': 'a relabel reaches every dropdown of every other spec',
  '04-approvals/decision-rules': 'changes decision rules for every route',
  '04-approvals/maker-checker': 'A-04 picker asserts exactly one user, which clones would break',
  '04-approvals/workflow-master': 'changes approval routes',
  '07-audit/audit-trail': 'exact counts on the shared audit list',
  '08-employee-payroll/19-g3-gap-fixes':
    'asserts seeded rows its own user acted on (V077_28): a clone has none',
};

/** The specs that need another suite's result (the locked cycle of the current period). */
export const DEPENDS_ON = [
  { match: /^06-exceptions-and-adjustments\/0[1-5]-|\/09-double-submit/, suite: 'cycle-serial' },
];

/** The hub screens serve every menu: a spec that only passes through them shares no screen state. */
export const HUB_SCREENS = ['W-01', 'W-02'];

/** The second view of the same specs: a spec carries every domain whose test is true. */
export const DOMAINS = {
  'authentication-otp': { path: /sign-in|login|otp|own-password|session-idle|step-up/ },
  'rbac-data-scope': {
    path: /access-control|office-|personas|role-screen|administration/,
    screens: ['A-01', 'A-02', 'A-03', 'A-04'],
  },
  'security-tampering': { path: /security-|access-control|content-security|two-users/ },
  'maker-checker': {
    path: /maker-checker|decision-rules|workflow-master|inbox-target|double-submit/,
    screens: ['W-01', 'W-02', 'A-08'],
  },
  'employee-hrms': {
    path: /employee-master|staff-transfers|hrms|onboarding|allowance-flags|list-paging/,
    screens: ['M-01', 'I-17', 'I-21', 'M-11', 'X-03', 'X-07'],
  },
  'payroll-cycle': {
    path: /05-payroll-processing|payroll-cycle/,
    screens: ['C-01', 'C-02', 'C-09', 'C-13', 'D-01', 'M-10'],
  },
  labour: { path: /labour|nwnp/, screens: ['M-02', 'M-05', 'M-06', 'M-09', 'I-01', 'I-02'] },
  cpf: { path: /cpf|opening-balance/, screens: ['I-09', 'I-22', 'C-12', 'M-19'] },
  'income-tax': { path: /tax/, screens: ['C-07', 'C-10', 'C-11', 'I-07', 'I-18', 'M-17'] },
  nps: { path: /nps/, screens: ['I-14'] },
  'loans-advances': { path: /loan/, screens: ['I-04'] },
  'arrears-retro': {
    path: /arrear|increment|wage-revision|g4-gap|final-settlement|settlement/,
    screens: ['I-05', 'I-08', 'I-24', 'I-25', 'M-03', 'O-05'],
  },
  'off-cycle-supplementary': { path: /off-cycle|supplementary/, screens: ['I-20', 'I-23'] },
  'masters-rates': {
    path: /03-masters\//,
    screens: ['M-04', 'M-07', 'M-08', 'M-14', 'M-15', 'M-18', 'M-22', 'M-23', 'M-24', 'M-25', 'M-26', 'M-27'],
  },
  'reference-data': { path: /reference-lists/, screens: ['M-16'] },
  'audit-history': { path: /07-audit|audit/, screens: ['A-05', 'A-07'] },
  reports: { path: /mis-reports|mis-statutory/, screens: ['M-12', 'O-04', 'R-01'] },
  'failure-paths': { path: /failure-paths|exceptions-worklist|double-submit/, screens: ['X-05'] },
  'i18n-responsive': { path: /phone-layout|tablet-layout|form-controls|personas-and-menus|list-paging/ },
  'pay-items': {
    path: /penalty|allowances-claims|leave|payslips|eseva/,
    screens: ['I-03', 'I-06', 'I-11', 'I-16', 'E-01', 'E-02', 'O-01'],
  },
};

/** Paths no suite depends on: changing them needs no browser test. */
export const NO_IMPACT = [
  'docs/',
  'backend/src/test/',
  'backend/src/integrationTest/',
  'backend/src/batchTest/',
  'backend/src/architectureTest/',
  'frontend/tests/',
  'frontend/e2e/README.md',
  'frontend/e2e/traceability.json',
  'db/rollback/',
  'db/oracle-setup/',
  'db/volume/',
  'db/edge/',
  'db/tools/',
  'scripts/perf/',
  'scripts/resilience/',
  'scripts/quality/',
  '.github/',
  'CLAUDE.md',
  'README.md',
];

/** Backend packages and frontend areas every screen stands on: a change maps to all suites. */
export const SHARED_BACKEND = ['platform', 'iam', 'workflow', 'audit', 'organisation', 'document'];
export const SHARED_FRONTEND = [
  'frontend/src/shared/',
  'frontend/src/app/',
  'frontend/src/styles/',
  'frontend/src/generated/',
  'frontend/src/modules/shell/',
  'frontend/src/modules/iam/',
  'frontend/src/modules/workflow/',
  'frontend/package.json',
  'frontend/package-lock.json',
  'frontend/next.config.ts',
  'frontend/playwright.config.ts',
  'frontend/e2e/suites.json',
  'frontend/e2e/lanes.ts',
  'backend/src/main/resources/',
  'backend/build.gradle.kts',
  'db/migration/',
  'db/e2e-parallel/',
  'scripts/local/',
  'config/',
];
