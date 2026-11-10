'use client';

import { createContext, useContext } from 'react';
import type { OtpProof } from '../api/workflowApi';

/**
 * OTP-gated actions (approval_workflow_action.otp_required, one of the 18 gated actions): the first call answers 428 with a
 * challenge id and an OTP is sent to the acting user's registered mobile (and e-mail); the
 * provider's dialog asks for the code and repeats the call with {@code otp: {challengeId, code}},
 * or without one for a new code (Resend). The sign-in OTP is the login card's own step (ADR-0026).
 */
export type GovernedCall<T> = (otp?: OtpProof) => Promise<T>;
export type GovernedRun = <T>(call: GovernedCall<T>) => Promise<T>;

/** Without a provider a call simply runs once (no OTP step). */
export const GovernedContext = createContext<GovernedRun>((call) => call());

/** Runs an API call with the OTP step; every module's maker-checker hooks go through it. */
export function useGoverned(): GovernedRun {
  return useContext(GovernedContext);
}
