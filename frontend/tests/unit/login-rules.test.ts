import { describe, expect, it } from 'vitest';
import { ApiError } from '@/shared/api';
import { asApiError } from '@/modules/iam/orchestration/authQueries';
import {
  countdown,
  mobileDigits,
  ID_TYPE,
  identifierProblem,
  otpDigits,
  otpProblem,
  passwordProblem,
  readRemembered,
  REMEMBER_COOKIE,
  rememberCookie,
} from '@/modules/iam/business/loginRules';

/** X-08 sign-in form rules (SRS-G-35, SRS-G-48 / OD-36, OD-37 Employee ID, OD-38 remember device). */
describe('the sign-in identifier', () => {
  it('SRS-G-35: an empty or blank user ID names what is missing for each method', () => {
    expect(identifierProblem('email', '')).toBe('login.emailRequired');
    expect(identifierProblem('email', '   ')).toBe('login.emailRequired');
    expect(identifierProblem('employee', '')).toBe('login.employeeIdRequired');
  });

  it('SRS-G-35: an e-mail needs a local part, an @, a domain and a dot; spaces around are trimmed', () => {
    for (const bad of ['name', 'name@', '@fci.gov.in', 'name@fci', 'na me@fci.gov.in', 'a@b@c.in']) {
      expect(identifierProblem('email', bad), bad).toBe('login.emailInvalid');
    }
    expect(identifierProblem('email', ' finance.mumbai@fci-demo.test ')).toBeNull();
  });

  it('OD-37: an Employee ID is not checked as an e-mail', () => {
    expect(identifierProblem('employee', 'EIS0012345')).toBeNull();
    expect(identifierProblem('employee', '12345')).toBeNull();
  });

  it('R-ASCII: a user ID outside printable ASCII is refused before it is sent, for both methods', () => {
    expect(identifierProblem('email', 'नाम@fci.gov.in')).toBe('login.asciiOnly');
    expect(identifierProblem('employee', 'EIS 123')).toBe('login.asciiOnly');
    expect(identifierProblem('employee', 'EIS\t123')).toBe('login.asciiOnly');
    expect(identifierProblem('employee', '~!#$%^&*()_+{}|:"<>?')).toBeNull();
  });

  it('maps each method to the API identifier type', () => {
    expect(ID_TYPE).toEqual({ email: 'USER_ID', employee: 'EMPLOYEE_ID', mobile: 'MOBILE' });
  });
});

describe('the password and the OTP', () => {
  it('SRS-G-35: only an empty password is refused here; spaces are part of a password', () => {
    expect(passwordProblem('')).toBe('login.passwordRequired');
    expect(passwordProblem(' ')).toBeNull();
    expect(passwordProblem('Fci@Demo2026')).toBeNull();
  });

  it('SRS-G-48: the code is exactly six digits', () => {
    for (const bad of ['', '12345', '1234567', '12a456', '12 456', '١٢٣٤٥٦']) {
      expect(otpProblem(bad), bad).toBe('login.otp.codeRequired');
    }
    expect(otpProblem('012345')).toBeNull();
    expect(otpProblem(' 012345 ')).toBeNull();
  });

  it('SRS-G-48: typed or pasted text keeps its first six digits only', () => {
    expect(otpDigits('12-34 56')).toBe('123456');
    expect(otpDigits('code: 9876543')).toBe('987654');
    expect(otpDigits('abc')).toBe('');
  });

  it('shows the validity as m:ss, never below zero', () => {
    expect(countdown(600)).toBe('10:00');
    expect(countdown(61)).toBe('1:01');
    expect(countdown(9.7)).toBe('0:09');
    expect(countdown(0)).toBe('0:00');
    expect(countdown(-5)).toBe('0:00');
  });
});

describe('remember this device (OD-38)', () => {
  it('writes the method and the trimmed user ID for 30 days, Strict, Secure on HTTPS only', () => {
    const cookie = rememberCookie({ method: 'email', identifier: ' a.b@fci.gov.in ' }, true);
    expect(cookie).toBe(
      `${REMEMBER_COOKIE}=email%7Ca.b%40fci.gov.in; Path=/; SameSite=Strict; Secure; Max-Age=2592000`,
    );
    expect(rememberCookie({ method: 'employee', identifier: 'EIS1' }, false)).not.toContain('Secure');
  });

  it('forgets with an empty, expired cookie', () => {
    expect(rememberCookie(null, false)).toBe(`${REMEMBER_COOKIE}=; Path=/; SameSite=Strict; Max-Age=0`);
  });

  it('reads back what it wrote, among other cookies', () => {
    const value = rememberCookie({ method: 'employee', identifier: 'EIS0012345' }, false).split(';')[0];
    expect(readRemembered(`FCI_LOCALE=hi; ${value}; XSRF-TOKEN=t`)).toEqual({
      method: 'employee',
      identifier: 'EIS0012345',
    });
  });

  it('ignores a missing, empty, malformed or tampered cookie', () => {
    for (const cookies of [
      '',
      'FCI_LOCALE=hi',
      `${REMEMBER_COOKIE}=`,
      `${REMEMBER_COOKIE}=%E0%A4`,
      `${REMEMBER_COOKIE}=nobar`,
      `${REMEMBER_COOKIE}=badge%7CX1`,
      `${REMEMBER_COOKIE}=email%7Cnot-an-email`,
      `${REMEMBER_COOKIE}=employee%7C`,
      `X${REMEMBER_COOKIE}=email%7Ca%40b.in`,
    ]) {
      expect(readRemembered(cookies), cookies).toBeNull();
    }
  });
});

describe('a failed step', () => {
  it('shows the API error as it came, and anything else as the network message', () => {
    const refused = new ApiError(401, { errorCode: 'AUTH-401-CREDENTIALS' }, 'c1');
    expect(asApiError(refused)).toBe(refused);
    expect(asApiError(new TypeError('offline')).messageKey).toBe('error.network');
    expect(asApiError('odd').status).toBe(0);
  });
});

describe('the registered mobile (OD-41)', () => {
  it('is required and ten digits starting 6 to 9, however it is typed', () => {
    expect(identifierProblem('mobile', '')).toBe('login.mobileRequired');
    expect(identifierProblem('mobile', '  ')).toBe('login.mobileRequired');
    for (const ok of ['9876543210', '+91 98765 43210', '098765-43210', '919876543210', '6000000000']) {
      expect(identifierProblem('mobile', ok), ok).toBeNull();
    }
    for (const bad of ['98765', '5876543210', '98765432101', 'abcdefghij', '+1 202 555 0100']) {
      expect(identifierProblem('mobile', bad), bad).toBe('login.mobileInvalid');
    }
  });

  it('keeps the ten digits of a number typed with a country code or a trunk zero', () => {
    expect(mobileDigits('+91-98765-43210')).toBe('9876543210');
    expect(mobileDigits('098765 43210')).toBe('9876543210');
    expect(mobileDigits('98765 43210')).toBe('9876543210');
    expect(mobileDigits('12')).toBe('12');
  });

  it('maps to the API type MOBILE and is remembered like the others', () => {
    expect(ID_TYPE.mobile).toBe('MOBILE');
    const value = rememberCookie({ method: 'mobile', identifier: '9876543210' }, false).split(';')[0];
    expect(readRemembered(value as string)).toEqual({ method: 'mobile', identifier: '9876543210' });
  });
});

describe('sign-in identifiers, boundaries', () => {
  it('the remember cookie is named FCI_REMEMBER', () => {
    expect(REMEMBER_COOKIE).toBe('FCI_REMEMBER');
  });

  it('only a +91 number of twelve digits loses its country code', () => {
    expect(mobileDigits('+91 98765 43210')).toBe('9876543210');
    expect(mobileDigits('9198765432')).toBe('9198765432');
    expect(mobileDigits('123456789012')).toBe('123456789012');
    expect(mobileDigits('09876543210')).toBe('9876543210');
    expect(mobileDigits('0987654321')).toBe('0987654321');
    expect(mobileDigits('98765432100')).toBe('98765432100');
  });

  it('an e-mail has no space or second @ after its domain', () => {
    expect(identifierProblem('email', 'a@b.in')).toBeNull();
    expect(identifierProblem('email', 'a@b.in x')).toBe('login.emailInvalid');
    expect(identifierProblem('email', 'a@b.in@c')).toBe('login.emailInvalid');
  });

  it('OD-38: a remembered e-mail sign-in is read back; a cookie without the bar is not', () => {
    expect(readRemembered(`${REMEMBER_COOKIE}=email%7Ca%40b.in`)).toEqual({
      method: 'email',
      identifier: 'a@b.in',
    });
    expect(readRemembered(`${REMEMBER_COOKIE}=employee1`)).toBeNull();
    expect(readRemembered(`${REMEMBER_COOKIE}=mobile%7C9876543210`)).toEqual({
      method: 'mobile',
      identifier: '9876543210',
    });
  });
});
