import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vitest/config';

/**
 * Mutation testing of the business rules (Stryker, stryker.config.json): the frontend's twin of PIT
 * on *.business.*. Only the business-rule unit tests run, by convention `tests/unit/*-rules.test.ts`
 * (pure functions, no screen, no DOM): a rule must be pinned by its own tests, as in the backend.
 * Stryker runs this config once per mutant with that mutant switched on, so it must stay fast:
 * Node environment, no setup file, and the run stops at the first failing test (the mutant is
 * killed). A module adds its rules' tests under the same naming convention.
 *
 * Sharded runs (scripts/mutation-shards.mjs, npm run mutation:shards) set FCI_MUTATION_TESTS to the
 * comma-separated rule tests that import one module's business files; only those run. Each run uses
 * ONE Vitest worker: Stryker already runs `concurrency` of these side by side, and a worker pool per
 * mutant run on top of that oversubscribed the CPU (90 %, owner 2026-11-08).
 */
const RULE_TESTS = 'tests/unit/*rules*.test.ts';
const RULE_TEST_FILE = /^tests\/unit\/[^/]*rules[^/]*\.test\.ts$/;

function ruleTests(): string[] {
  const listed = (process.env.FCI_MUTATION_TESTS ?? '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);
  if (listed.length === 0) return [RULE_TESTS];
  const strays = listed.filter((f) => !RULE_TEST_FILE.test(f));
  if (strays.length)
    throw new Error(`FCI_MUTATION_TESTS names files that are not rule tests: ${strays.join(', ')}`);
  return listed;
}

export default defineConfig({
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  test: {
    environment: 'node',
    include: ruleTests(),
    bail: 1,
    maxWorkers: 1,
    coverage: { enabled: false },
    reporters: ['dot'],
  },
});
