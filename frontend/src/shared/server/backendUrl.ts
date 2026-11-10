import { readFileSync } from 'node:fs';

/**
 * Where the Next.js server finds the online backend (BACKEND_URL), the same release for every
 * environment (local, dev, uat, prod): the address is a per-environment setting that lives OUTSIDE
 * the release, so a new deployment needs no edit.
 *
 * Order (first one that gives an http(s) address wins):
 *  1. the BACKEND_URL environment variable (a unit file, a start script, a test);
 *  2. a `web.env` file with the line `BACKEND_URL=...` in the environment's config folder:
 *     $FCI_WEB_ENV (a file), then $FCI_CONFIG_DIR/web.env, then /etc/fci/web.env, then
 *     /data/fci/frontend/web.env (the DEV server's frontend folder), then
 *     /data/fci/web.env (the folder is the one the backend already uses, `fci.config.dir`).
 * In production there is NO built-in address (owner 2026-11-09, OD-158: fail fast): when neither gives
 * a valid address the server refuses to start (src/instrumentation.ts) and every call fails with
 * {@link BackendUrlMissing}, instead of silently using an address that may be another environment's.
 * Under `next dev` the backend the dev server proxies /api to (FCI_DEV_BACKEND_URL) is used, so server
 * rendering and the browser's own calls see the same session store.
 */
const DEV_BACKEND = 'http://localhost:8180';

type Env = Record<string, string | undefined>;

/** The files looked at, in order; empty entries are skipped. */
export function webEnvFiles(env: Env): string[] {
  const dir = env.FCI_CONFIG_DIR?.trim().replace(/\/+$/, '');
  return [
    env.FCI_WEB_ENV?.trim(),
    dir ? `${dir}/web.env` : undefined,
    '/etc/fci/web.env',
    '/data/fci/frontend/web.env',
    '/data/fci/web.env',
  ]
    .filter((f): f is string => Boolean(f))
    .filter((f, i, all) => all.indexOf(f) === i);
}

/** KEY=VALUE lines; `#` comments and blank lines are skipped; one pair of quotes is removed. */
export function parseEnvFile(text: string): Record<string, string> {
  const out: Record<string, string> = {};
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    const eq = line.indexOf('=');
    if (eq < 1) continue;
    const value = line
      .slice(eq + 1)
      .trim()
      .replace(/^(["'])(.*)\1$/, '$2');
    out[line.slice(0, eq).trim()] = value;
  }
  return out;
}

/** An http(s) address without a trailing slash, or null (a typo is reported as "not configured"). */
export function cleanAddress(value: string | undefined): string | null {
  const v = value?.trim().replace(/\/+$/, '');
  if (!v) return null;
  try {
    const url = new URL(v);
    return (url.protocol === 'http:' || url.protocol === 'https:') && url.hostname ? v : null;
  } catch {
    return null;
  }
}

/** The production web server has no valid BACKEND_URL: the message tells the operator what to create. */
export class BackendUrlMissing extends Error {
  constructor(files: string[]) {
    super(
      'BACKEND_URL is not configured: set the BACKEND_URL environment variable or create a web.env file with the ' +
        `line BACKEND_URL=http://127.0.0.1:<backend port>/fci-payroll in one of: ${files.join(', ')} ` +
        '(config/env/web.env.example).',
    );
    this.name = 'BackendUrlMissing';
  }
}

export function resolveBackendUrl(
  env: Env = process.env,
  // eslint-disable-next-line security/detect-non-literal-fs-filename -- an operator's config file path
  read: (file: string) => string = (f) => readFileSync(f, 'utf8'),
): string {
  const fromEnv = cleanAddress(env.BACKEND_URL);
  if (fromEnv) return fromEnv;
  if (env.NODE_ENV !== 'production') {
    return `${env.FCI_DEV_BACKEND_URL ?? DEV_BACKEND}/api`;
  }
  for (const file of webEnvFiles(env)) {
    let text: string;
    try {
      text = read(file);
    } catch {
      continue; // not there on this machine
    }
    const fromFile = cleanAddress(parseEnvFile(text).BACKEND_URL);
    if (fromFile) return fromFile;
  }
  throw new BackendUrlMissing(webEnvFiles(env));
}
