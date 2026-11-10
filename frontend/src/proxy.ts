import { type NextRequest, NextResponse } from 'next/server';

/**
 * Content-Security-Policy for every rendered page, with a fresh nonce per request (ADR-0010 §3).
 * Next.js reads the nonce from the request's CSP header and puts it on its own scripts; no
 * 'unsafe-inline', no 'unsafe-eval' in production, everything else same-origin. NGINX passes this
 * header through and adds the other security headers itself.
 */
export function proxy(request: NextRequest) {
  const nonce = btoa(crypto.randomUUID());
  const dev = process.env.NODE_ENV !== 'production';
  const csp = [
    "default-src 'none'",
    `script-src 'self' 'nonce-${nonce}' 'strict-dynamic'${dev ? " 'unsafe-eval'" : ''}`,
    // next dev injects style tags for hot reload; production uses only same-origin stylesheets.
    `style-src 'self'${dev ? " 'unsafe-inline'" : ''}`,
    "img-src 'self' data:",
    "font-src 'self'",
    `connect-src 'self'${dev ? ' ws:' : ''}`,
    "manifest-src 'self'",
    "worker-src 'self'",
    "form-action 'self'",
    "frame-ancestors 'none'",
    "base-uri 'none'",
    "object-src 'none'",
  ].join('; ');

  const requestHeaders = new Headers(request.headers);
  requestHeaders.set('x-nonce', nonce);
  requestHeaders.set('Content-Security-Policy', csp);
  const response = NextResponse.next({ request: { headers: requestHeaders } });
  response.headers.set('Content-Security-Policy', csp);
  response.headers.set('Cache-Control', 'no-store');
  return response;
}

export const config = {
  matcher: [
    {
      // Pages only: not the backend (/nginx/fci-payroll, proxied by NGINX), not hashed build assets, not the
      // public PWA files (manifest, icons, service worker, offline page), which browsers may cache.
      source:
        '/((?!fci-payroll/|nginx/fci-payroll/|api/|_next/static|_next/image|favicon.ico|manifest.webmanifest|icons/|sw.js|offline.html|offline.css).*)',
      missing: [
        { type: 'header', key: 'next-router-prefetch' },
        { type: 'header', key: 'purpose', value: 'prefetch' },
      ],
    },
    {
      // The sign-in page itself under a base path ('/nginx', next.config.ts): Next.js prefixes the
      // source above to '/nginx/(...)', which the bare '/nginx' does not match; without this entry
      // the page would go out without its CSP and nonce.
      source: '/',
      missing: [
        { type: 'header', key: 'next-router-prefetch' },
        { type: 'header', key: 'purpose', value: 'prefetch' },
      ],
    },
  ],
};
