import type { NextConfig } from 'next';

/**
 * Server-side rendering (client checklist): `next build` produces a standalone Node server
 * (.next/standalone/server.js) that NGINX proxies "/" to; NGINX proxies "/nginx/fci-payroll/" to the online
 * backend. Pages are rendered per request with the caller's session (src/lib/server/session.ts) and
 * carry a per-request CSP nonce (src/proxy.ts). In `next dev` only, /nginx/fci-payroll/* is rewritten to the local
 * backend so the browser sees a single origin, as it does behind NGINX.
 */
const isProduction = process.env.NODE_ENV === 'production';
const devBackend = process.env.FCI_DEV_BACKEND_URL ?? 'http://localhost:8180';

/** The build stamp: names the service worker's caches, so each deployment starts with fresh ones. */
const appBuild = process.env.FCI_APP_BUILD ?? new Date().toISOString().replace(/\D/g, '').slice(0, 14);

function basePath(fromBuild: string | undefined): string {
  if (fromBuild === undefined || fromBuild === '') return '/nginx';
  return fromBuild === '/' ? '' : fromBuild;
}

const pageBase = basePath(process.env.FCI_BASE_PATH);

const config: NextConfig = {
  output: 'standalone',
  // '/nginx' (commit d112a4f) unless the build says otherwise: FCI_BASE_PATH='/' serves the app at
  // the root, as the local stack (scripts/local/deploy-local.ps1) and its E2E suite expect. Not '':
  // Windows PowerShell 5.1 removes a variable set to an empty string, and the default would apply.
  basePath: pageBase,
  // FCI_PAGE_BASE: the same path for the code that builds URLs itself (src/shared/ui/basePath.ts).
  env: { FCI_APP_BUILD: appBuild, FCI_PAGE_BASE: pageBase },
  reactStrictMode: true,
  poweredByHeader: false,
  productionBrowserSourceMaps: false,
  images: { unoptimized: true },
  ...(isProduction
    ? {}
    : {
        async rewrites() {
          return [
            {
              source: '/nginx/fci-payroll/:path*',
              destination: `${devBackend}/api/:path*`,
              basePath: false,
            },
          ];
        },
      }),
};

export default config;
