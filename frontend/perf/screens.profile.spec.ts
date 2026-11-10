import { writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { type CDPSession, expect, type Page, test } from '@playwright/test';
import { t, workAs } from '../e2e/support/app';

/**
 * A module's screens in the browser (owner 2026-10-25; PERF_MODULE picks the journeys, 08 by
 * default): for each screen of the module's journeys a
 * cold load (first visit) and warm loads (median of RUNS), each a full navigation as a user opening
 * the screen would make it. Measured per load: time to the screen's heading and to network idle,
 * Largest Contentful Paint, long tasks (> 50 ms main-thread blocks), Chrome's script / layout /
 * style time, the JS and API bytes and calls, the slowest API call. Across all loads: the JS heap
 * after a forced GC at the start and at the end (a growing heap is a leak). Best practice: warm-up
 * excluded from the medians, fixed journey, numbers written to docs/perf for the next comparison.
 */
const RUNS = Number(process.env.PERF_RUNS ?? 5);
const MODULE = process.env.PERF_MODULE ?? '08';
const OUT = resolve(
  dirname(fileURLToPath(import.meta.url)),
  '..',
  '..',
  'docs',
  'perf',
  `module-${MODULE}-browser.json`,
);

interface Load {
  headingMs: number;
  idleMs: number;
  lcpMs: number;
  longTasks: number;
  longTaskMs: number;
  scriptMs: number;
  layoutMs: number;
  styleMs: number;
  jsKb: number;
  jsFiles: number;
  apiCalls: number;
  apiKb: number;
  slowestApiMs: number;
  slowestApi: string;
}

type Journey = { login: string; scope: { role: string; office: string }; screens: string[] };

const JOURNEYS_BY_MODULE: Record<string, Journey[]> = {
  '08': [
    {
      login: 'tax.maker.delhi@fci-demo.test',
      scope: { role: 'PAYROLL_MAKER', office: 'DH001' },
      screens: ['I-07', 'I-18', 'C-07', 'C-10', 'C-11', 'C-13'],
    },
    {
      login: 'tax.ruleadmin@fci-demo.test',
      scope: { role: 'RULE_ADMINISTRATOR', office: 'HQ001' },
      screens: ['M-17', 'M-18'],
    },
  ],
  // Module 10 (I-03, I-16) with the screens of this pass's other work (C-02 pre-run checks, M-10
  // cycle parameters; M-07 catalogue and M-08 simulation of module 06).
  '10': [
    {
      login: 'maker.delhi@fci-demo.test',
      scope: { role: 'PAYROLL_MAKER', office: 'DH001' },
      screens: ['I-03', 'I-16', 'C-02', 'M-10'],
    },
    {
      login: 'su.hq@fci-demo.test',
      scope: { role: 'RULE_ADMINISTRATOR', office: 'HQ001' },
      screens: ['M-07', 'M-08'],
    },
  ],
};

// Deep pass (owner 2026-10-27) of the modules not profiled before, on the VOLUME set (deploy-local.ps1 -Volume): the
// office desks are the Punjab region's (the largest region, about 6,000 members); Headquarters screens as before.
const PUNJAB_MAKER = {
  login: 'vol.maker.pb@fci-demo.test',
  scope: { role: 'PAYROLL_MAKER', office: 'RO-PB' },
};
const PUNJAB_CHECKER = {
  login: 'vol.checker.pb@fci-demo.test',
  scope: { role: 'PAYROLL_CHECKER', office: 'RO-PB' },
};
const RULE_ADMIN = { login: 'su.hq@fci-demo.test', scope: { role: 'RULE_ADMINISTRATOR', office: 'HQ001' } };
Object.assign(JOURNEYS_BY_MODULE, {
  '01': [{ ...PUNJAB_MAKER, screens: ['X-05', 'X-06', 'X-07', 'I-20', 'I-23'] }],
  '02': [
    { ...PUNJAB_CHECKER, screens: ['W-01', 'W-02'] },
    {
      login: 'sysadmin@fci-demo.test',
      scope: { role: 'SYSTEM_ADMINISTRATOR', office: 'HQ001' },
      screens: ['A-01', 'A-02', 'A-03', 'A-04', 'A-07', 'A-08'],
    },
  ],
  '03': [
    {
      login: 'superuser.hq@fci-demo.test',
      scope: { role: 'SUPER_USER', office: 'HQ001' },
      screens: ['A-05'],
    },
  ],
  '04': [
    {
      ...RULE_ADMIN,
      screens: ['M-11', 'M-14', 'M-15', 'M-16', 'M-22', 'M-23', 'M-24', 'M-25', 'M-26', 'M-27'],
    },
  ],
  '05': [{ ...PUNJAB_MAKER, screens: ['D-01', 'C-01', 'C-02', 'C-09', 'I-19', 'M-01'] }],
  '06': [{ ...RULE_ADMIN, screens: ['M-07', 'M-08'] }],
  '07': [
    {
      login: 'cpf.manager.north@fci-demo.test',
      scope: { role: 'CPF_MANAGER', office: 'NA11' },
      screens: ['I-09', 'I-12', 'I-22', 'C-12', 'M-19'],
    },
  ],
  '09': [{ ...PUNJAB_MAKER, screens: ['I-04'] }],
  '12': [{ ...PUNJAB_MAKER, screens: ['I-06'] }],
});

const JOURNEYS = JOURNEYS_BY_MODULE[MODULE] ?? [];

/** Observers that live in the page from its first script: long tasks and LCP. */
const OBSERVERS = () => {
  const w = window as unknown as { __perf: { longTasks: number[]; lcp: number } };
  w.__perf = { longTasks: [], lcp: 0 };
  new PerformanceObserver((l) => l.getEntries().forEach((e) => w.__perf.longTasks.push(e.duration))).observe({
    type: 'longtask',
    buffered: true,
  });
  new PerformanceObserver((l) => {
    const last = l.getEntries().at(-1);
    if (last) w.__perf.lcp = last.startTime;
  }).observe({ type: 'largest-contentful-paint', buffered: true });
};

async function metrics(cdp: CDPSession): Promise<Record<string, number>> {
  const { metrics: m } = await cdp.send('Performance.getMetrics');
  return Object.fromEntries(m.map((x) => [x.name, x.value]));
}

async function heapAfterGc(cdp: CDPSession): Promise<number> {
  await cdp.send('HeapProfiler.collectGarbage');
  return Math.round(((await metrics(cdp)).JSHeapUsedSize ?? 0) / 1024);
}

async function load(page: Page, cdp: CDPSession, screen: string): Promise<Load> {
  const before = await metrics(cdp);
  const start = Date.now();
  await page.goto(`/payroll/?s=${screen}`);
  await expect(page.getByRole('heading', { level: 1, name: t('en', `screen.${screen}`) })).toBeVisible();
  const headingMs = Date.now() - start;
  await page.waitForLoadState('networkidle');
  const idleMs = Date.now() - start;
  const after = await metrics(cdp);
  const page_ = await page.evaluate(() => {
    const w = window as unknown as { __perf: { longTasks: number[]; lcp: number } };
    const resources = performance.getEntriesByType('resource') as PerformanceResourceTiming[];
    const js = resources.filter((r) => r.initiatorType === 'script' || r.name.endsWith('.js'));
    const api = resources.filter((r) => r.name.includes('/nginx/fci-payroll/'));
    const slowest = api.reduce<PerformanceResourceTiming | null>(
      (s, r) => (!s || r.duration > s.duration ? r : s),
      null,
    );
    return {
      lcp: w.__perf.lcp,
      longTasks: w.__perf.longTasks,
      jsBytes: js.reduce((n, r) => n + (r.transferSize || r.encodedBodySize), 0),
      jsFiles: js.length,
      apiCalls: api.length,
      apiBytes: api.reduce((n, r) => n + (r.transferSize || r.encodedBodySize), 0),
      slowestMs: slowest ? slowest.duration : 0,
      // The path only: never a query value (no member or amount reaches the report).
      slowest: slowest ? new URL(slowest.name).pathname : '',
    };
  });
  const delta = (k: string) => Math.round(((after[k] ?? 0) - (before[k] ?? 0)) * 1000);
  return {
    headingMs,
    idleMs,
    lcpMs: Math.round(page_.lcp),
    longTasks: page_.longTasks.length,
    longTaskMs: Math.round(page_.longTasks.reduce((a, b) => a + b, 0)),
    scriptMs: delta('ScriptDuration'),
    layoutMs: delta('LayoutDuration'),
    styleMs: delta('RecalcStyleDuration'),
    jsKb: Math.round(page_.jsBytes / 1024),
    jsFiles: page_.jsFiles,
    apiCalls: page_.apiCalls,
    apiKb: Math.round(page_.apiBytes / 1024),
    slowestApiMs: Math.round(page_.slowestMs),
    slowestApi: page_.slowest,
  };
}

function median(loads: Load[]): Load {
  const pick = <K extends keyof Load>(k: K): Load[K] => {
    const sorted = [...loads].sort((a, b) => Number(a[k]) - Number(b[k]));
    return (sorted[Math.floor(sorted.length / 2)] as Load)[k];
  };
  const out = {} as Load;
  for (const k of Object.keys(loads[0] as Load) as (keyof Load)[]) {
    (out as unknown as Record<string, unknown>)[k] = pick(k);
  }
  return out;
}

test(`Module ${MODULE} screens: load, main-thread, network and heap profile`, async ({ page }) => {
  expect(JOURNEYS.length, `no journeys for module ${MODULE}`).toBeGreaterThan(0);
  await page.addInitScript(OBSERVERS);
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('Performance.enable');
  const report: Record<string, { cold: Load; warm: Load }> = {};
  let heapStartKb = 0;
  for (const journey of JOURNEYS) {
    await workAs(page, 'en', journey.login, journey.scope, journey.screens[0] as string);
    if (heapStartKb === 0) heapStartKb = await heapAfterGc(cdp);
    for (const screen of journey.screens) {
      const cold = await load(page, cdp, screen);
      const warm: Load[] = [];
      for (let i = 0; i < RUNS; i++) warm.push(await load(page, cdp, screen));
      report[screen] = { cold, warm: median(warm) };
    }
  }
  const heapEndKb = await heapAfterGc(cdp);
  const result = {
    takenAt: new Date().toISOString(),
    baseUrl: test.info().project.use.baseURL,
    runsPerScreen: RUNS,
    heapAfterGcKb: { start: heapStartKb, end: heapEndKb },
    screens: report,
  };
  // eslint-disable-next-line security/detect-non-literal-fs-filename -- docs/perf, MODULE limited to the journey keys above
  writeFileSync(OUT, `${JSON.stringify(result, null, 2)}\n`);
  for (const [screen, r] of Object.entries(report)) {
    // The SRS-NF-6 response target is 3 s per screen; a warm load stays well inside it.
    expect.soft(r.warm.idleMs, `${screen} warm load`).toBeLessThan(3000);
  }
});
