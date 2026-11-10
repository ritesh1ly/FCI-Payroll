/**
 * The path the pages are served under (next.config.ts basePath: '/nginx' by default, as the shared
 * NGINX deployment has it; '' when built with FCI_BASE_PATH='/'). Next.js adds it to its router,
 * <Link>, redirects and imported assets by itself; a plain <a href>, a fetch of a page route, a
 * public file (icons, manifest, service worker) or a metadata URL need it from here. The API uses
 * its own fixed prefix, /nginx/fci-payroll, independently of this page base path.
 */
export const BASE_PATH = process.env.FCI_PAGE_BASE ?? '';

/** {@code path} (starting with '/') under the pages' base path. */
export function withBase(path: string): string {
  return `${BASE_PATH}${path}`;
}
