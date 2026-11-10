/**
 * X-08 sign-in form rules (pure; the server checks the same: @NotBlank, @PrintableAscii, a
 * six-digit OTP). SRS-G-35 sign-in, SRS-G-48 the OTP to the registered mobile (OD-36), the Employee
 * ID (EIS number) as the user ID (OD-37), the registered mobile (OD-41) and the remembered sign-in
 * of this device (OD-38).
 */

/**
 * The ways to name the account: the official e-mail (the login ID), the EIS number, or the
 * registered mobile ("Sign in with OTP on registered mobile").
 */
export type SignInMethod = 'email' | 'employee' | 'mobile';

/** The API's identifier type of each method (POST /auth/login idType). */
export const ID_TYPE: Record<SignInMethod, 'USER_ID' | 'EMPLOYEE_ID' | 'MOBILE'> = {
  email: 'USER_ID',
  employee: 'EMPLOYEE_ID',
  mobile: 'MOBILE',
};

/** The wireframe's e-mail check: something @ something . something, no spaces. */
const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
/** R-ASCII: the database character set is US7ASCII, so a user ID is printable ASCII only. */
const PRINTABLE_ASCII = /^[\x20-\x7E]*$/;
/** The OTP is exactly six digits (AuthController LoginOtpRequest). */
const SIX_DIGITS = /^\d{6}$/;
/** An Indian mobile number: ten digits starting 6 to 9. */
const MOBILE = /^[6-9]\d{9}$/;

/** A mobile as typed (spaces, dashes, +91 or a leading 0) reduced to its ten digits. */
export function mobileDigits(value: string): string {
  const digits = value.replace(/\D/g, '');
  if (digits.length === 12 && digits.startsWith('91')) return digits.slice(2);
  if (digits.length === 11 && digits.startsWith('0')) return digits.slice(1);
  return digits;
}

/** The message key of what is wrong with the user ID, or null when it may be sent. */
export function identifierProblem(method: SignInMethod, value: string): string | null {
  const v = value.trim();
  if (method === 'mobile') {
    if (!v) return 'login.mobileRequired';
    return MOBILE.test(mobileDigits(v)) ? null : 'login.mobileInvalid';
  }
  if (!v) return method === 'email' ? 'login.emailRequired' : 'login.employeeIdRequired';
  if (!PRINTABLE_ASCII.test(v)) return 'login.asciiOnly';
  if (method === 'email' && !EMAIL.test(v)) return 'login.emailInvalid';
  return null;
}

/** The message key of a missing password, or null. Never trimmed: spaces may be part of it. */
export function passwordProblem(value: string): string | null {
  return value ? null : 'login.passwordRequired';
}

/** The message key of a code that is not six digits, or null. */
export function otpProblem(value: string): string | null {
  return SIX_DIGITS.test(value.trim()) ? null : 'login.otp.codeRequired';
}

/** Keeps digits only, at most six: what a user types or pastes into the OTP field. */
export function otpDigits(value: string): string {
  return value.replace(/\D/g, '').slice(0, 6);
}

/** mm:ss of {@code seconds} left (never negative). */
export function countdown(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}

/**
 * "Remember this device" (OD-38): the sign-in method and the user ID, in a first-party cookie of
 * this origin for 30 days. Never the password, and the OTP is still asked: it saves typing, it
 * trusts nothing. A cookie (not localStorage, which stays empty: ADR-0010 §6).
 */
export const REMEMBER_COOKIE = 'FCI_REMEMBER';
const REMEMBER_DAYS = 30;

export interface RememberedSignIn {
  method: SignInMethod;
  identifier: string;
}

/** The remembered sign-in in {@code cookies} (document.cookie), or null when none or unreadable. */
export function readRemembered(cookies: string): RememberedSignIn | null {
  const raw = cookies
    .split('; ')
    .find((part) => part.startsWith(`${REMEMBER_COOKIE}=`))
    ?.slice(REMEMBER_COOKIE.length + 1);
  if (!raw) return null;
  let decoded: string;
  try {
    decoded = decodeURIComponent(raw);
  } catch {
    return null;
  }
  const bar = decoded.indexOf('|');
  const method = decoded.slice(0, bar);
  const identifier = decoded.slice(bar + 1);
  if (bar < 0 || (method !== 'email' && method !== 'employee' && method !== 'mobile')) return null;
  return identifierProblem(method, identifier) ? null : { method, identifier };
}

/** The Set-Cookie text that remembers {@code value}, or forgets it (null). */
export function rememberCookie(value: RememberedSignIn | null, secure: boolean): string {
  const attributes = `; Path=/; SameSite=Strict${secure ? '; Secure' : ''}`;
  if (!value) return `${REMEMBER_COOKIE}=${attributes}; Max-Age=0`;
  const content = encodeURIComponent(`${value.method}|${value.identifier.trim()}`);
  return `${REMEMBER_COOKIE}=${content}${attributes}; Max-Age=${REMEMBER_DAYS * 24 * 60 * 60}`;
}
