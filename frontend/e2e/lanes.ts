/**
 * What Playwright needs to know of the parallel run besides e2e/suites.json (e2e/README.md "Suites"). Read by
 * playwright.config.ts only when E2E_LANES is set, so `--project edge` is the same serial suite as ever.
 */

/**
 * The workers of a parallel step (owner 2026-11-07: 8, one per physical core). The clones are w1 to
 * w7, so no more: a ninth worker would have nobody to sign in as. E2E_WORKERS may ask for fewer.
 */
export const MAX_WORKERS = 8;

export function parallelWorkers(wanted = process.env.E2E_WORKERS): number {
  const asked = Number(wanted ?? MAX_WORKERS);
  return Number.isInteger(asked) && asked > 0 ? Math.min(asked, MAX_WORKERS) : MAX_WORKERS;
}
