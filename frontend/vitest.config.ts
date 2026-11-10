import react from '@vitejs/plugin-react';
import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  plugins: [react()],
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./tests/setup.ts'],
    include: ['tests/unit/**/*.test.{ts,tsx}'],
    // Screen tests drive whole user flows with user-event; under a parallel backend build the
    // default 5 s is too tight (timeouts, not failures). Owner 2026-11-09: 30 s (load-sensitive
    // screen tests failed at 20 s inside parallel coverage runs and passed alone).
    testTimeout: 30000,
    // CPU-friendly runs (owner rule): cap the worker pool at half the cores. At full parallelism
    // with coverage instrumentation, CPU starvation let a screen's async react-query miss the
    // 1000 ms findBy default (e.g. O-04's catalogue falling back to its single default option) -
    // a contention flake, not a logic failure. Half the pool leaves each worker time to settle.
    maxWorkers: '50%',
    coverage: {
      provider: 'v8',
      reporter: ['text-summary', 'lcov'],
      // Every module and shared building block. Screens are also covered end to end by the
      // Playwright suite against the real stack (e2e/); the generated bundles are data.
      include: ['src/modules/**/*.{ts,tsx}', 'src/shared/**/*.{ts,tsx}'],
      exclude: ['src/generated/**', '**/*.d.ts', '**/index.ts'],
      // Hard gates of the project owner, revised 2026-11-03 (as the backend's, gradle.properties):
      // lines 97 %, branches 95 %, functions and statements 97 % (mutation 95 %:
      // stryker.config.json). Recommendation unchanged: 100 % lines, branches and mutation, above
      // 95 % for the rest; 100 % for the critical features (the money, statutory and rule-engine
      // screens and rules; a module adds its files when it is built).
      // OD-156 (owner 2026-11-09): the global coverage hard gates are 85 % so the build runs in GitHub
      // Actions; the recommendation stays 100 % (2026-11-09: lines 99.91, branches 98.32). The per-file
      // 100 % gates of the critical files below are unchanged; mutation stays 95 %.
      thresholds: {
        lines: 85,
        branches: 85,
        functions: 85,
        statements: 85,
        'src/modules/configuration/business/ruleRules.ts': {
          lines: 100,
          branches: 100,
          functions: 100,
          statements: 100,
        },
        // OD-154 (owner 2026-11-09): branch gate at what tests can reach; the 11 unreachable arms of the
        // M-18/M-19 register block are listed in PENDING COV-UNREACH-FE-2026-11-09.
        'src/modules/configuration/components/RuleCatalogue.tsx': {
          lines: 100,
          branches: 97,
          functions: 100,
          statements: 100,
        },
        'src/modules/configuration/api/rulesApi.ts': {
          lines: 100,
          branches: 100,
          functions: 100,
          statements: 100,
        },
        // OD-154: line 33 `key ?? ''` sits in a query disabled while the key is null (PENDING COV-UNREACH-FE-2026-11-09).
        'src/modules/configuration/orchestration/ruleQueries.ts': {
          lines: 100,
          branches: 75,
          functions: 100,
          statements: 100,
        },
      },
    },
  },
});
