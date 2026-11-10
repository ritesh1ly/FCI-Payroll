/**
 * Masking of what a log entry carries (R-PII; BR-SEC-001, BR-SEC-002; SRS-7-23): personal and
 * financial data never reaches the console. Two layers: a key that names such data is redacted
 * whatever its value; any text is scrubbed of the identifier patterns (PAN, Aadhaar, account and
 * mobile numbers, e-mail addresses). Sizes are capped so one entry stays small (no over-logging).
 */

export const REDACTED = '[redacted]';

/** Words that make a key personal, secret or financial: its value is redacted whatever it is. */
const SENSITIVE_WORDS = [
  'pan',
  'aadhaar',
  'aadhar',
  'uan',
  'pran',
  'account',
  'ifsc',
  'mobile',
  'phone',
  'email',
  'mail',
  'birth',
  'dob',
  'password',
  'passwd',
  'secret',
  'token',
  'otp',
  'cookie',
  'authorization',
  'salary',
  'gross',
  'net',
  'basic',
  'pay',
  'amount',
  'tds',
  'tax',
  'income',
  'deduction',
  'allowance',
  'arrear',
  'balance',
  'loan',
  'cpf',
  'nps',
  'rent',
  'premium',
  'address',
  'name',
];

/** Keys that look sensitive but carry only technical identifiers, kept for diagnosis. */
const SAFE_KEYS = new Set([
  'errorCode',
  'messageKey',
  'correlationId',
  'status',
  'method',
  'path',
  'event',
  'screen',
  'level',
  'count',
  'ms',
  'kind',
  'repeated',
]);

const PATTERNS: [RegExp, (m: string) => string][] = [
  // PAN: five letters, four digits, a letter.
  [/\b[A-Z]{5}\d{4}[A-Z]\b/g, (m) => `XXXXXX${m.slice(6)}`],
  // E-mail addresses.
  [/[\w.+-]{1,64}@[\w.-]{1,253}/g, () => '[email]'],
  // Aadhaar grouped by four.
  [/\b\d{4}[ -]\d{4}[ -]\d{4}\b/g, (m) => `XXXXXXXX${m.slice(-4)}`],
  // Aadhaar, bank accounts, UAN, PRAN, mobiles: a run of nine or more digits keeps its last four.
  [/\b\d{9,18}\b/g, (m) => `${'X'.repeat(m.length - 4)}${m.slice(-4)}`],
];

const MAX_TEXT = 300;
const MAX_ITEMS = 10;
const MAX_DEPTH = 3;
const MAX_KEYS = 20;

/** Text with the identifier patterns masked and the length capped. */
export function maskText(text: string): string {
  const masked = PATTERNS.reduce((s, [re, to]) => s.replace(re, to), text);
  return masked.length > MAX_TEXT ? `${masked.slice(0, MAX_TEXT)}…(${masked.length} chars)` : masked;
}

/** A URL path with every identifier segment (one holding a digit) as :id, and no query values. */
export function maskPath(path: string): string {
  const [route = '', query] = path.split('?');
  const masked = route
    .split('/')
    // An identifier is letters and digits holding a digit (an id, an EIS, a reference); a
    // resource name (form16-certificates, v1) keeps its text.
    .map((segment) => (/^[0-9A-Za-z_]{3,}$/.test(segment) && /\d/.test(segment) ? ':id' : segment))
    .join('/');
  return query === undefined ? masked : `${masked}?…`;
}

function isSensitive(key: string): boolean {
  if (SAFE_KEYS.has(key)) return false;
  const lower = key.toLowerCase();
  return SENSITIVE_WORDS.some((word) => lower.includes(word));
}

function sanitizeScalar(value: unknown): { done: true; value: unknown } | { done: false } {
  if (value === null || value === undefined || typeof value === 'boolean' || typeof value === 'number')
    return { done: true, value };
  if (typeof value === 'string') return { done: true, value: maskText(value) };
  if (typeof value === 'bigint' || typeof value === 'symbol') return { done: true, value: String(value) };
  if (typeof value === 'function') return { done: true, value: '[function]' };
  if (value instanceof Error) return { done: true, value: describeError(value) };
  if (typeof FormData !== 'undefined' && value instanceof FormData)
    return { done: true, value: '[form data]' };
  if (typeof Blob !== 'undefined' && value instanceof Blob)
    return { done: true, value: `[file ${value.size} bytes]` };
  return { done: false };
}

function sanitizeEntry(key: string, value: unknown, depth: number): unknown {
  if (isSensitive(key)) return REDACTED;
  if (key === 'path' && typeof value === 'string') return maskPath(value);
  return sanitize(value, depth + 1);
}

/** A value made safe to log: sensitive keys redacted, text masked, sizes and depth capped. */
export function sanitize(value: unknown, depth = 0): unknown {
  const scalar = sanitizeScalar(value);
  if (scalar.done) return scalar.value;
  if (depth >= MAX_DEPTH) return '[…]';
  if (Array.isArray(value)) {
    const items = value.slice(0, MAX_ITEMS).map((v) => sanitize(v, depth + 1));
    return value.length > MAX_ITEMS ? [...items, `…(${value.length} items)`] : items;
  }
  const entries = Object.entries(value as Record<string, unknown>);
  const out: Record<string, unknown> = {};
  for (const [key, v] of entries.slice(0, MAX_KEYS)) out[key] = sanitizeEntry(key, v, depth);
  if (entries.length > MAX_KEYS) out['…'] = `${entries.length} keys`;
  return out;
}

function textOf(value: unknown): string {
  if (typeof value === 'string') return value;
  try {
    return JSON.stringify(sanitize(value)) ?? typeof value;
  } catch {
    return typeof value;
  }
}

/** What an error tells for diagnosis: its kind, the API problem fields, a masked message. */
export function describeError(error: unknown, withStack = false): Record<string, unknown> {
  if (!(error instanceof Error)) return { kind: typeof error, message: maskText(textOf(error)) };
  const e = error as Error & { status?: number; code?: string; messageKey?: string; correlationId?: string };
  const out: Record<string, unknown> = { kind: e.name, message: maskText(e.message) };
  if (e.status !== undefined) out.status = e.status;
  if (e.code !== undefined) out.errorCode = e.code;
  if (e.messageKey !== undefined) out.messageKey = e.messageKey;
  if (e.correlationId !== undefined) out.correlationId = e.correlationId;
  if (withStack && e.stack) out.stack = e.stack.split('\n').slice(0, 6).map(maskText).join('\n');
  return out;
}
