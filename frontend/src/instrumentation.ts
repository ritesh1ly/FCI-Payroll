/**
 * Next.js start-up hook (runs once when the server starts, not during `next build`).
 *
 * OD-158 (owner 2026-11-09): the production web server refuses to start without a configured backend
 * address (BACKEND_URL variable or a web.env outside the release, see shared/server/backendUrl.ts), so a
 * missing or mistyped setting is seen at once on the server, instead of as a sign-in loop or calls to
 * another environment's backend.
 */
export async function register(): Promise<void> {
  await checkBackendUrl(process.env, (code) => process.exit(code));
}

type Env = Record<string, string | undefined>;

/** The check itself, with the environment and the exit injectable for tests. */
export async function checkBackendUrl(env: Env, exit: (code: number) => void): Promise<void> {
  // Only the Node.js server in production; `next build` sets NEXT_PHASE and has no web.env.
  if (env.NEXT_RUNTIME !== 'nodejs' || env.NODE_ENV !== 'production') return;
  if (env.NEXT_PHASE === 'phase-production-build') return;
  const { resolveBackendUrl } = await import('./shared/server/backendUrl');
  try {
    resolveBackendUrl(env);
  } catch (cause) {
    // Plain stderr (the unit's journal): the lint forbids console, and nothing else is set up this early.
    process.stderr.write(`[fci-frontend] ${(cause as Error).message}\n`);
    exit(1);
  }
}
