import { BRAND } from '@/shared/ui/brand';
import { withBase } from '@/shared/ui/basePath';
import type { Metadata, Viewport } from 'next';
import { cookies } from 'next/headers';
import type { ReactNode } from 'react';
import { devanagari, geist, geistMono, loginFonts } from './fonts';
import { Providers } from './providers';
import '@/styles/brand.css';
import '@/styles/live/global.css';
import '@/generated/wireframe.css';
import '@/styles/fcilekha.css';
import '@/styles/workspace.css';
import '@/styles/forms.css';
import '@/styles/login.css';

export const metadata: Metadata = {
  title: 'FCILekha | Food Corporation of India',
  description: 'FCILekha — integrated payroll of the Food Corporation of India',
  robots: { index: false, follow: false },
  referrer: 'strict-origin-when-cross-origin',
  formatDetection: { telephone: false, email: false, address: false },
  // PWA-ready (public/manifest.webmanifest): installable once a service worker is enabled.
  manifest: withBase('/manifest.webmanifest'),
  applicationName: 'FCILekha',
  icons: {
    icon: [
      { url: withBase('/icons/icon-32.png'), sizes: '32x32', type: 'image/png' },
      { url: withBase('/icons/icon-192.png'), sizes: '192x192', type: 'image/png' },
    ],
    apple: [{ url: withBase('/icons/apple-touch-icon.png'), sizes: '180x180' }],
  },
  appleWebApp: { capable: true, title: 'FCILekha', statusBarStyle: 'default' },
};

/**
 * Zoom is never blocked (WCAG 1.4.4: no maximum-scale / user-scalable=no); viewport-fit=cover lets the
 * shell use the safe-area insets of notched phones and of the installed app. Theme colour: the
 * wireframe navy (--navy).
 */
export const viewport: Viewport = {
  width: 'device-width',
  initialScale: 1,
  viewportFit: 'cover',
  colorScheme: 'light',
  themeColor: BRAND.green,
};

/**
 * Every page is rendered per request: the session decides what is shown, and the CSP nonce
 * (src/proxy.ts) must be fresh on every response, including the 404 page.
 */
export const dynamic = 'force-dynamic';

/** The live site's root classes; the document language comes from the FCI_LOCALE cookie. */
export default async function RootLayout({ children }: { children: ReactNode }) {
  const locale = (await cookies()).get('FCI_LOCALE')?.value === 'hi' ? 'hi' : 'en';
  return (
    <html
      lang={locale}
      className={`${geist.variable} ${geistMono.variable} ${devanagari.variable} ${loginFonts} h-full antialiased`}
    >
      <body className="min-h-full flex flex-col">
        <noscript>
          {/* i18n-ignore: shown only without JavaScript, so it cannot use the bundles; both languages. */}
          <p>This application requires JavaScript. / इस एप्लिकेशन के लिए JavaScript आवश्यक है।</p>
        </noscript>
        {/* PWA off unless the Next.js server runs with FCI_PWA_ENABLED=true (read per request). */}
        <Providers pwa={process.env.FCI_PWA_ENABLED === 'true'} version={process.env.FCI_APP_BUILD ?? 'dev'}>
          {children}
        </Providers>
      </body>
    </html>
  );
}
