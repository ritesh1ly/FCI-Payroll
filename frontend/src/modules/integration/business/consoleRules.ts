/** R-ASCII: printable ASCII with tab and line breaks, as the server's @PrintableAscii accepts (business/ may not import the UI). */
const PLAIN_TEXT = /^[\x20-\x7E\t\r\n]*$/;

/** The reason column's length (audit reason, 500). */
export const CONSOLE_REASON_MAX = 500;

/** The result of reading one JSON box: the value, or the label key of why it is refused. */
export type Parsed<T> = { ok: true; value: T } | { ok: false; error: string };

/** X-03 HRMS API console: the outcome pill (ACCEPTED ok, REJECTED warn, INVALID_REQUEST info, the rest bad). */
export function outcomeTone(outcome: string): string {
  if (outcome === 'ACCEPTED') return 'p-ok';
  if (outcome === 'REJECTED') return 'p-warn';
  if (outcome === 'INVALID_REQUEST') return 'p-info';
  return 'p-bad';
}

/**
 * The query box: empty is no parameters; otherwise a JSON object whose values are all strings
 * (the backend sends them as the URL query of the call).
 */
export function parseQuery(text: string): Parsed<Record<string, string>> {
  if (text.trim() === '') return { ok: true, value: {} };
  let value: unknown;
  try {
    value = JSON.parse(text);
  } catch {
    return { ok: false, error: 'x03.console.err.badJson' };
  }
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    return { ok: false, error: 'x03.console.err.queryNotObject' };
  }
  const entries = Object.entries(value as Record<string, unknown>);
  if (entries.some(([, v]) => typeof v !== 'string')) {
    return { ok: false, error: 'x03.console.err.queryNotString' };
  }
  return { ok: true, value: value as Record<string, string> };
}

/** The body box: empty is no body (null); otherwise any valid JSON. */
export function parseBody(text: string): Parsed<unknown> {
  if (text.trim() === '') return { ok: true, value: null };
  try {
    return { ok: true, value: JSON.parse(text) as unknown };
  } catch {
    return { ok: false, error: 'x03.console.err.badJson' };
  }
}

/** The reason: required, printable ASCII (R-ASCII), at most 500 characters; the label key of the refusal, or null. */
export function reasonError(reason: string): string | null {
  const text = reason.trim();
  if (text === '') return 'x03.console.err.reasonRequired';
  if (!PLAIN_TEXT.test(text)) return 'x03.console.err.reasonAscii';
  if (text.length > CONSOLE_REASON_MAX) return 'x03.console.err.reasonLength';
  return null;
}

/** Why Run is not offered (the label key), or null: an API is chosen, both JSON boxes read, the reason holds. */
export function runBlocked(input: {
  code: string;
  query: string;
  body: string;
  reason: string;
}): string | null {
  if (input.code === '') return 'x03.console.why.api';
  const q = parseQuery(input.query);
  if (!q.ok) return q.error;
  const b = parseBody(input.body);
  if (!b.ok) return b.error;
  return reasonError(input.reason);
}
