'use client';

import { useCallback, useEffect, useRef } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { ApiError, onStepUpRequired } from '@/shared/api';
import { meKey } from '@/shared/session';
import { useGoverned } from '@/modules/workflow';
import { authApi } from '../api/authApi';

/**
 * The OTP step-up of sensitive screens (SRS-G-48 / SRS-G-21, Module 02 D-2) for the workspace:
 * registers the API client's AUTH-403-STEP-UP handler (the refused call runs again once the user
 * confirms) and returns {@code ensureStepUp} for the checks the workspace makes itself (a sensitive
 * role at sign-in, opening a sensitive screen). Single-flight: calls refused at the same moment share
 * one OTP dialog. Resolves false when the user cancels.
 */
export function useStepUp(): () => Promise<boolean> {
  const governed = useGoverned();
  const queryClient = useQueryClient();
  const pending = useRef<Promise<boolean> | null>(null);

  const ensureStepUp = useCallback((): Promise<boolean> => {
    pending.current ??= governed((otp) => authApi.stepUp(otp))
      .then(async () => {
        await queryClient.invalidateQueries({ queryKey: meKey });
        return true;
      })
      .catch((error: unknown) => {
        if (error instanceof ApiError && error.messageKey === 'otp.cancelled') return false;
        throw error;
      })
      .finally(() => {
        pending.current = null;
      });
    return pending.current;
  }, [governed, queryClient]);

  useEffect(() => {
    onStepUpRequired(ensureStepUp);
    return () => onStepUpRequired(null);
  }, [ensureStepUp]);

  return ensureStepUp;
}
