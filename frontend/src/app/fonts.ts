import localFont from 'next/font/local';

/**
 * Self-hosted fonts (OFL): no Google Fonts or CDN, so the CSP stays `font-src 'self'`. The variable
 * names are the live site's (--font-geist-sans, --font-geist-mono); Noto Sans Devanagari renders
 * Hindi labels.
 */
export const geist = localFont({
  src: '../../node_modules/geist/dist/fonts/geist-sans/Geist-Variable.woff2',
  variable: '--font-geist-sans',
  display: 'swap',
  weight: '100 900',
});

export const geistMono = localFont({
  src: '../../node_modules/geist/dist/fonts/geist-mono/GeistMono-Variable.woff2',
  variable: '--font-geist-mono',
  display: 'swap',
  weight: '100 900',
});

export const devanagari = localFont({
  src: '../../node_modules/@fontsource-variable/noto-sans-devanagari/files/noto-sans-devanagari-devanagari-wght-normal.woff2',
  variable: '--font-devanagari',
  display: 'swap',
  weight: '100 900',
});

/*
 * The login wireframe's three faces (payroll-login-wireframe_1.html), self-hosted: Plus Jakarta Sans
 * for the display text, Inter for the body, DM Serif Display italic for the headline's second line.
 * Declared on every page (layout.tsx) but not preloaded: a browser fetches them only where they are
 * used, the sign-in page (login.css).
 */
export const jakarta = localFont({
  src: '../../node_modules/@fontsource-variable/plus-jakarta-sans/files/plus-jakarta-sans-latin-wght-normal.woff2',
  variable: '--font-jakarta',
  display: 'swap',
  preload: false,
  weight: '200 800',
});

export const inter = localFont({
  src: '../../node_modules/@fontsource-variable/inter/files/inter-latin-wght-normal.woff2',
  variable: '--font-inter',
  display: 'swap',
  preload: false,
  weight: '100 900',
});

export const dmSerif = localFont({
  src: '../../node_modules/@fontsource/dm-serif-display/files/dm-serif-display-latin-400-italic.woff2',
  variable: '--font-dm-serif',
  display: 'swap',
  preload: false,
  weight: '400',
  style: 'italic',
});

/** The variables of the sign-in page's fonts, for the root element. */
export const loginFonts = `${jakarta.variable} ${inter.variable} ${dmSerif.variable}`;
