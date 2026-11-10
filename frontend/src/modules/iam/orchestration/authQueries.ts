'use client';

import { useCallback } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError } from '@/shared/api';
import { clearAppCaches } from '@/shared/pwa';
import { fetchMe, type Me, meKey } from '@/shared/session';
import { type OtpProof, useGoverned } from '@/modules/workflow';
import { authApi, type Credentials, type LoginAnswer } from '../api/authApi';
import { readRemembered, rememberCookie } from '../business/loginRules';

/** What a failed step shows: the API's error, or (anything else) the network message. */
export function asApiError(cause: unknown): ApiError {
  return cause instanceof ApiError ? cause : new ApiError(0, {}, undefined);
}

export function useLoginOptions() {
  return useQuery({ queryKey: ['login-options'], queryFn: authApi.loginOptions });
}

/**
 * Step 1 of sign-in: the password. Signed in at once (every cached query belongs to the previous,
 * anonymous session, so all are refreshed), or an OTP was sent (SRS-G-48): nothing to refresh yet.
 */
export function useLogin() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (credentials: Credentials) => authApi.login(credentials),
    onSuccess: (answer: LoginAnswer) => (answer.otpRequired ? undefined : queryClient.invalidateQueries()),
  });
}

/** "Sign in with OTP on registered mobile" (OD-41): sends the code to the number. */
export function useLoginMobile() {
  return useMutation({ mutationFn: (mobile: string) => authApi.loginMobile(mobile) });
}

/** Step 2: the OTP opens the session. */
export function useLoginOtp() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (code: string) => authApi.loginOtp(code),
    onSuccess: () => queryClient.invalidateQueries(),
  });
}

/** A new sign-in code. */
export function useResendLoginOtp() {
  return useMutation({ mutationFn: () => authApi.resendLoginOtp() });
}

/** Step 1 of a forgotten password: an OTP to the registered e-mail (never reveals the account). */
export function useRequestReset() {
  return useMutation({ mutationFn: (loginId: string) => authApi.requestReset(loginId) });
}

/** Step 2: OTP and the new password. */
export function useResetPassword() {
  return useMutation({
    mutationFn: (input: { loginId: string; code: string; newPassword: string }) =>
      authApi.resetPassword(input.loginId, input.code, input.newPassword),
  });
}

/** The own password change; the OTP dialog asks for the code the server sends (SRS-G-21, X-08). */
export function useChangePassword() {
  const governed = useGoverned();
  return useMutation({
    mutationFn: (input: { currentPassword: string; newPassword: string }) =>
      governed((otp) => authApi.changePassword(input.currentPassword, input.newPassword, otp)),
  });
}

/** X-08: the OTP-gated actions as the approval routes configure them. */
export function useOtpGatedActions() {
  return useQuery({ queryKey: ['otp', 'gated-actions'], queryFn: authApi.otpGatedActions });
}

/**
 * X-08 "Change my password" with the OTP typed on the card: the first call answers 428 with the challenge (the OTP is
 * sent to the registered contact), the second carries the challenge and the code (SRS-G-21).
 */
export function useChangePasswordOnCard() {
  return useMutation({
    mutationFn: (input: { currentPassword: string; newPassword: string; otp?: OtpProof }) =>
      authApi.changePassword(input.currentPassword, input.newPassword, input.otp),
  });
}

/** Signs out (best effort) and forgets every cached query of the session. */
export function useSignOut() {
  const queryClient = useQueryClient();
  return async () => {
    // Sign-out clears every cookie of the origin (Clear-Site-Data); a sign-in this device was asked
    // to remember (OD-38: method and user ID, never a secret) is written back afterwards.
    const remembered = readRemembered(document.cookie);
    await authApi.logout().catch(() => undefined);
    if (remembered) document.cookie = rememberCookie(remembered, window.location.protocol === 'https:');
    queryClient.clear();
    // The service worker's caches hold only public assets, but nothing outlives a session on a device.
    await clearAppCaches();
  };
}

/** Switches the session's scope; resolves to the new session (fresh menu, permissions, landing). */
export function useSwitchScope() {
  const queryClient = useQueryClient();
  const governed = useGoverned();
  return useMutation({
    mutationFn: async (input: { role: string; location: string | null }): Promise<Me> => {
      // Another role asks for the OTP first (the governed dialog), another office never does.
      await governed((otp) => authApi.switchScope(input.role, input.location, otp));
      // OD-130: the new session's permissions first, in the cache before anything refetches: an open
      // screen the new role may not read must not ask for it (a refused call is an offence).
      const next = await fetchMe();
      queryClient.setQueryData(meKey, next);
      // Reset every cached answer of the previous office and role: its data is cleared (no screen shows
      // the previous scope's data, not even for a moment) and every open screen's query refetches at once
      // for the new scope. The national reference lists stay.
      await queryClient.resetQueries({
        predicate: (query) => query.queryKey[0] !== 'reference' && query.queryKey[0] !== meKey[0],
      });
      return next;
    },
  });
}

/** The payroll periods of the top-bar selector (newest first). */
export function usePeriods(enabled: boolean) {
  return useQuery({ queryKey: ['periods'], queryFn: authApi.periods, enabled });
}

/**
 * Works on another payroll period: every cached answer of the previous period is reset (as for an
 * office switch) and the profile, which carries the period, is fetched again.
 */
export function useSwitchPeriod() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (period: string): Promise<Me> => {
      await authApi.switchPeriod(period);
      await queryClient.resetQueries({ predicate: (query) => query.queryKey[0] !== 'reference' });
      return queryClient.fetchQuery({ queryKey: meKey, queryFn: fetchMe });
    },
  });
}

/** After a password change: reload everything of the session (the forced-change flag clears). */
export function useRefreshSession() {
  const queryClient = useQueryClient();
  return () => queryClient.invalidateQueries();
}

/**
 * One light call that restarts the server session's idle timer (the profile only): the inactivity
 * guard uses it while the user works without calling the server, and for "Stay signed in".
 */
export function useKeepAlive() {
  const queryClient = useQueryClient();
  return useCallback(() => queryClient.refetchQueries({ queryKey: meKey }), [queryClient]);
}
