import js from '@eslint/js';
import nextPlugin from '@next/eslint-plugin-next';
import jsxA11y from 'eslint-plugin-jsx-a11y';
import reactHooks from 'eslint-plugin-react-hooks';
import security from 'eslint-plugin-security';
import globals from 'globals';
import tseslint from 'typescript-eslint';

/**
 * Modular frontend (mirrors the backend's modules, docs/ARCHITECTURE.md):
 *
 *   src/app/                    thin Next.js routes: compose module public APIs only
 *   src/shared/<area>/          the base every module uses (api client, i18n, session, time, ui, server)
 *   src/modules/<module>/       one business function
 *     index.ts                  its public API: the ONLY entry point for other modules and app/
 *     api/                      HTTP calls and the DTO types of its endpoints (no React)
 *     business/                 pure rules (no React, no I/O, no framework)
 *     orchestration/            use cases as hooks: TanStack Query keys, caching, invalidation
 *     components/               React components: render and delegate to orchestration
 *
 * MODULE_DEPENDENCIES lists, per module, the other modules it may use (through index.ts only).
 * `shell` is the composition root (workspace frame, screen registry): it may use every module and
 * no module may use it. Enforced with no-restricted-imports below.
 */
const MODULE_DEPENDENCIES = {
  audit: [],
  // The History button of a master screen (SRS-20-29) comes from the audit module.
  configuration: ['workflow', 'audit'],
  // M-18 / M-19 show their rule-engine registers through the M-08 catalogue.
  // I-22 takes its evidence through the order-copy control of SRS-G-30 (general).
  // M-18 also holds the tax section and PT state cards of Module 08 (tax).
  cpf: ['workflow', 'audit', 'configuration', 'general', 'tax'],
  employee: ['workflow', 'audit', 'general'],
  general: ['workflow'],
  iam: ['workflow', 'audit'],
  // C-01 shows the TDS cases flagged for the Checker's review of the cycle (tax, FRS V0.5 §14.4).
  jobs: ['workflow', 'audit', 'tax'],
  loans: ['general'],
  leave: ['audit'],
  nps: [],
  eseva: [],
  reporting: ['audit'],
  penalty: ['general'],
  settlement: [],
  increment: ['general', 'audit'],
  integration: [],
  arrear: [],
  labour: ['audit'],
  allowance: ['general'],
  workflow: ['audit'],
  tax: ['workflow', 'audit', 'general'],
  shell: [
    'allowance',
    'arrear',
    'audit',
    'configuration',
    'cpf',
    'employee',
    'eseva',
    'general',
    'iam',
    'increment',
    'integration',
    'jobs',
    'labour',
    'leave',
    'loans',
    'nps',
    'penalty',
    'reporting',
    'settlement',
    'tax',
    'workflow',
  ],
};
const MODULES = Object.keys(MODULE_DEPENDENCIES);

const DEEP_MODULE_IMPORT = {
  group: ['@/modules/*/*'],
  message:
    'Use another module only through its public API (@/modules/<name>); inside a module import relatively.',
};

/** What each layer may not import (on top of the module boundaries). */
const LAYER_RULES = {
  api: [
    {
      group: ['react', 'react-dom', '@tanstack/*', 'next', 'next/*'],
      message: 'api/ is plain HTTP + types: no React or query state.',
    },
    {
      group: ['../orchestration/*', '../components/*', '../business/*'],
      message: 'api/ is the lowest layer of a module.',
    },
  ],
  business: [
    {
      group: [
        'react',
        'react-dom',
        '@tanstack/*',
        'next',
        'next/*',
        '@/shared/api',
        '@/shared/ui',
        '@/shared/i18n',
        '@/shared/server',
        '@/shared/reference',
      ],
      message: 'business/ holds pure rules: no React, no I/O, no UI.',
    },
    {
      group: ['../api/*'],
      allowTypeImports: true,
      message: 'business/ may use the api/ types, never the calls.',
    },
    {
      group: ['../orchestration/*', '../components/*'],
      message: 'business/ must not depend on orchestration/ or components/.',
    },
  ],
  orchestration: [{ group: ['../components/*'], message: 'orchestration/ must not depend on components/.' }],
  components: [
    {
      group: ['../api/*'],
      allowTypeImports: true,
      message: 'Components use the orchestration/ hooks, not the api/ calls.',
    },
    { group: ['@tanstack/*'], message: 'Server state belongs in orchestration/ hooks.' },
  ],
};

function moduleBoundaries(name, layer) {
  const forbidden = MODULES.filter((m) => m !== name && !MODULE_DEPENDENCIES[name].includes(m));
  return [
    DEEP_MODULE_IMPORT,
    ...(forbidden.length
      ? [
          {
            group: forbidden.map((m) => `@/modules/${m}`),
            message: `Module "${name}" may use only: ${MODULE_DEPENDENCIES[name].join(', ') || 'no other module'} (MODULE_DEPENDENCIES).`,
          },
        ]
      : []),
    { group: ['@/app/*'], message: 'Modules never import routes.' },
    {
      group: ['@/shared/server', '@/shared/server/*', '@/generated/wireframes.json'],
      message:
        'Server-only: the wireframe bodies and server helpers never reach the browser bundle (route /payroll/wireframe/<screen> serves them after the VIEW check).',
    },
    {
      group: [layer ? '../../*' : '../*'],
      message: 'Do not reach out of the module with relative paths; use @/shared or a module API.',
    },
    ...(layer ? LAYER_RULES[layer] : []),
  ];
}

const boundaries = [
  ...MODULES.flatMap((name) => [
    {
      files: [`src/modules/${name}/index.ts`],
      rules: { 'no-restricted-imports': ['error', { patterns: moduleBoundaries(name, null) }] },
    },
    ...Object.keys(LAYER_RULES).map((layer) => ({
      files: [`src/modules/${name}/${layer}/**/*.{ts,tsx}`],
      rules: {
        'no-restricted-imports': [
          'error',
          {
            patterns: moduleBoundaries(name, layer),
            ...(layer === 'components'
              ? {
                  paths: [
                    {
                      name: '@/shared/api',
                      importNames: ['api'],
                      message: 'Components call the API through orchestration/ hooks.',
                    },
                  ],
                }
              : {}),
          },
        ],
      },
    })),
  ]),
  {
    files: ['src/shared/**/*.{ts,tsx}'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              group: ['@/modules/*', '@/modules/*/*', '@/app/*'],
              message: 'shared/ is the base layer: it never depends on a module or a route.',
            },
          ],
        },
      ],
    },
  },
  {
    files: ['src/app/**/*.{ts,tsx}'],
    rules: { 'no-restricted-imports': ['error', { patterns: [DEEP_MODULE_IMPORT] }] },
  },
];

/**
 * Flat config. Besides the usual correctness rules it enforces the frontend security rules of
 * ADR-0010: no browser storage outside preferences.ts, no raw HTML injection, no inline styles
 * (CSP), no eval-like APIs; and the module boundaries above.
 */
export default tseslint.config(
  {
    ignores: [
      '.next/**',
      'out/**',
      'coverage/**',
      'playwright-report/**',
      'test-results/**',
      'next-env.d.ts',
      // Stryker's sandbox copy and its report (npm run mutation), as in .gitignore.
      '.stryker-tmp/**',
      'reports/mutation/**',
    ],
  },
  js.configs.recommended,
  ...tseslint.configs.strict,
  security.configs.recommended,
  jsxA11y.flatConfigs.strict,
  {
    plugins: { '@next/next': nextPlugin, 'react-hooks': reactHooks },
    languageOptions: { globals: { ...globals.browser, ...globals.node } },
    rules: {
      ...nextPlugin.configs.recommended.rules,
      ...nextPlugin.configs['core-web-vitals'].rules,
      'react-hooks/rules-of-hooks': 'error',
      'react-hooks/exhaustive-deps': 'error',
      // The shared text area and month picker render a native textarea / input (UI audit 2026-11-02).
      'jsx-a11y/label-has-associated-control': [
        'error',
        { controlComponents: ['TextAreaField', 'MonthField', 'FinancialYearSelect'], depth: 3 },
      ],
      'no-eval': 'error',
      'no-implied-eval': 'error',
      'no-new-func': 'error',
      'no-script-url': 'error',
      'no-restricted-globals': [
        'error',
        { name: 'localStorage', message: 'Never store data in browser storage (ADR-0010 §6).' },
        { name: 'sessionStorage', message: 'Never store data in browser storage (ADR-0010 §6).' },
        { name: 'indexedDB', message: 'Never store data in browser storage (ADR-0010 §6).' },
      ],
      'no-restricted-properties': [
        'error',
        {
          object: 'window',
          property: 'localStorage',
          message: 'Never store data in browser storage (ADR-0010 §6).',
        },
        {
          object: 'window',
          property: 'sessionStorage',
          message: 'Never store data in browser storage (ADR-0010 §6).',
        },
        { property: 'innerHTML', message: 'Render text with React; never inject HTML.' },
        { property: 'outerHTML', message: 'Render text with React; never inject HTML.' },
      ],
      'no-restricted-syntax': [
        'error',
        {
          selector: "JSXAttribute[name.name='style']",
          message: 'Inline styles are blocked by the CSP (style-src without unsafe-inline). Use a class.',
        },
        {
          selector: "JSXAttribute[name.name='dangerouslySetInnerHTML']",
          message: 'Never inject HTML (XSS). Render text with React.',
        },
      ],
      'security/detect-object-injection': 'off',
      '@typescript-eslint/no-non-null-assertion': 'error',
      // Logging goes through @/shared/log, which masks personal and financial data (R-PII).
      'no-console': ['error'],
    },
  },
  ...boundaries,
  {
    files: ['src/shared/log/logger.ts', 'scripts/**', 'e2e/**', 'tests/**', '*.config.{js,mjs,ts}'],
    rules: { 'no-console': 'off' },
  },
  {
    files: ['src/lib/preferences.ts', 'src/lib/api/client.ts'],
    rules: { 'no-restricted-properties': 'off' },
  },
  {
    // Build-time scripts over files of this repository (export directory, design pack, bundles).
    files: ['scripts/**'],
    rules: { 'security/detect-non-literal-fs-filename': 'off', 'security/detect-unsafe-regex': 'off' },
  },
  {
    // E2E specs read browser storage only to assert that it stays empty, and read the local API
    // log for demo OTP codes.
    files: ['e2e/**'],
    rules: { 'no-restricted-properties': 'off', 'security/detect-non-literal-fs-filename': 'off' },
  },
  {
    files: ['tests/**', 'e2e/**', '**/*.test.ts', '**/*.test.tsx'],
    rules: { 'security/detect-non-literal-regexp': 'off' },
  },
);
