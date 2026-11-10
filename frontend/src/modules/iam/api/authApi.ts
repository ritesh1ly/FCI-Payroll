import { api } from '@/shared/api';
import type { LoginOptions } from '@/shared/session';
import type { OtpProof } from '@/modules/workflow';

export interface Credentials {
  loginId: string;
  password: string;
  /** The user ID: the login ID (official e-mail), the Employee ID (EIS, OD-37) or the mobile (OD-41). */
  idType?: 'USER_ID' | 'EMPLOYEE_ID' | 'MOBILE';
}

/** 200: signed in; the workspace route. */
export interface SignedIn {
  route: string;
  otpRequired?: undefined;
}

/** 202 (SRS-G-48, OD-36): the password was right; an OTP went to the masked destination. */
export interface OtpSent {
  otpRequired: true;
  channel: 'SMS' | 'EMAIL';
  destination: string;
  validSeconds: number;
}

export type LoginAnswer = SignedIn | OtpSent;

/**
 * The only place the iam module talks to /api/auth: sign-in options, sign-in (password, then the
 * OTP sent to the registered mobile when the server asks for it), forgotten-password reset by OTP,
 * password change. Failures of the public endpoints are shown on the form ({@code silent}), not as
 * global errors.
 */
export const authApi = {
  loginOptions: () => api<LoginOptions>('/auth/login-options', { silent: true }),
  login: (credentials: Credentials) =>
    api<LoginAnswer>('/auth/login', { method: 'POST', silent: true, body: credentials }),
  /**
   * "Sign in with OTP on registered mobile" (OD-41): the code goes to the account carrying the
   * number; an unregistered number gets the same answer and no code (no enumeration).
   */
  loginMobile: (mobile: string) =>
    api<OtpSent>('/auth/login/mobile', { method: 'POST', silent: true, body: { mobile } }),
  /** X-08 step 2: the code of this browser's pending sign-in. */
  loginOtp: (code: string) =>
    api<SignedIn>('/auth/login/otp', { method: 'POST', silent: true, body: { code } }),
  /** A new code for the pending sign-in; the earlier one stops working. */
  resendLoginOtp: () => api<OtpSent>('/auth/login/otp/resend', { method: 'POST', silent: true }),
  requestReset: (loginId: string) =>
    api('/auth/password/forgot', { method: 'POST', silent: true, body: { loginId } }),
  resetPassword: (loginId: string, code: string, newPassword: string) =>
    api('/auth/password/reset', { method: 'POST', silent: true, body: { loginId, code, newPassword } }),
  /** The own password change, confirmed with the user's OTP (SRS-G-21): 428 with the challenge first. */
  changePassword: (currentPassword: string, newPassword: string, otp?: OtpProof) =>
    api('/auth/password/change', {
      method: 'POST',
      body: { currentPassword, newPassword, ...(otp ? { otp } : {}) },
    }),
  logout: () => api('/auth/logout', { method: 'POST', silent: true }),
  /** X-08: the eighteen OTP-gated actions with their screens and the roles that enter the OTP (SRS-G-21). */
  otpGatedActions: () => api<GatedAction[]>('/v1/otp/gated-actions'),
  /**
   * Another (office, acting role) of the user's own RBAC, without signing out; `location` is null
   * for Employee (eSeva). The server refuses any scope the user does not hold. Another ROLE needs
   * the user's OTP unless one was confirmed recently (428 first; SRS-G-48, Module 02 D-2).
   */
  switchScope: (role: string, location: string | null, otp?: OtpProof) =>
    api('/auth/switch-scope', {
      method: 'POST',
      body: { role, ...(location ? { location } : {}), ...(otp ? { otp } : {}) },
    }),
  /**
   * The OTP step-up of the session's sensitive screens: 428 with the challenge first, then the
   * code; 204 when nothing needs confirming.
   */
  stepUp: (otp?: OtpProof) =>
    api('/auth/step-up', { method: 'POST', noStepUp: true, body: otp ? { otp } : {} }),
  /** The approved M-10 calendar periods with each cycle's state at the session's paying unit. */
  periods: () => api<PayrollPeriod[]>('/v1/periods'),
  /** The period the session works on (current or back-dated). */
  switchPeriod: (period: string) => api('/auth/period', { method: 'PUT', body: { period } }),
};

/** One payroll period of the top-bar selector (GET /v1/periods). */
export interface PayrollPeriod {
  period: string;
  finYear: string;
  /** The cycle state at the session's paying unit, or null when no cycle was initiated. */
  status: string | null;
  current: boolean;
}

/** One OTP-gated action of X-08: its code, the screens of its approval routes and the roles that enter the OTP. */
/** One OTP-gated action of X-08: the drawn screens, the screens of its configured routes and their roles. */
export interface GatedAction {
  code: string;
  screens: string[];
  routedScreens: string[];
  roles: string[];
}
