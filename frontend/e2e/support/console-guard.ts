import { expect, type Page } from '@playwright/test';

/**
 * R-PII in the browser console (owner 2026-10-25): every page a spec signs in on is watched for the
 * whole test, and a console message carrying personal or financial data is a (soft) failure of
 * that test. The E2E stack is built with NEXT_PUBLIC_LOG_LEVEL=debug, so every masked log entry
 * the frontend writes is checked, not only its warnings and errors.
 */
const SENSITIVE: [string, RegExp][] = [
  ['a PAN', /\b[A-Z]{5}\d{4}[A-Z]\b/],
  ['an Aadhaar, account or mobile number', /\b\d{9,18}\b/],
  ['an Aadhaar number', /\b\d{4} \d{4} \d{4}\b/],
  ['an e-mail address', /[\w.+-]+@[\w-]+\.[\w.-]+/],
  ['a rupee amount', /₹\s?\d/],
  ['the demo password', /Fci@Demo2026/],
];

const watched = new WeakSet<Page>();

/** What a console text carries that it must not, or null. */
export function sensitiveIn(text: string): string | null {
  const hit = SENSITIVE.find(([, re]) => re.test(text));
  return hit ? hit[0] : null;
}

/** Watches the page's console for the rest of the test (once per page). */
export function guardConsole(page: Page) {
  if (watched.has(page)) return;
  watched.add(page);
  page.on('console', (message) => {
    const text = message.text();
    const found = sensitiveIn(text);
    expect.soft(found, `console.${message.type()} carries ${found}: ${text.slice(0, 200)}`).toBeNull();
  });
}
