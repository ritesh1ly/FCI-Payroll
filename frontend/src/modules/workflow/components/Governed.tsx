'use client';

import { useCallback, useMemo, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { ApiError } from '@/shared/api';
import { GovernedContext, type GovernedCall, type GovernedRun } from '../orchestration/governed';
import { DemoOtpCode, useDialogFocus } from '@/shared/ui';
import { useDemoSession } from '../orchestration/demoSession';
import { useI18n } from '@/shared/i18n';

/** What the user answers in the OTP dialog: the code, Cancel, or Resend (a new code). */
type Answer = { code: string } | 'cancel' | 'resend';

/**
 * The OTP dialog of governed calls (see orchestration/governed.ts) for everything below it. On a
 * wrong or expired code it says so and offers Resend (SRS-EH-13): the call is made again without a
 * code, the server issues a new challenge and the earlier code stops working. At the server's
 * limits (attempts on a challenge, codes an hour) the answer is not a challenge: it is shown, and
 * the user may cancel and start again.
 */
export function GovernedProvider({ children }: { children: ReactNode }) {
  const { t } = useI18n();
  const [challenge, setChallenge] = useState<number | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [resent, setResent] = useState(false);
  const [resending, setResending] = useState(false);
  const pending = useRef<{ resolve: (answer: Answer) => void } | null>(null);
  const dialog = useDialogFocus<HTMLFormElement>(challenge !== null);
  // OD-146: on a demo stack the dialog offers the user's own last code.
  const demo = useDemoSession();

  const ask = useCallback((challengeId: number) => {
    setChallenge(challengeId);
    return new Promise<Answer>((resolve) => {
      pending.current = { resolve };
    });
  }, []);

  const run = useCallback<GovernedRun>(
    async <T,>(call: GovernedCall<T>): Promise<T> => {
      try {
        return await call();
      } catch (first) {
        if (!(first instanceof ApiError) || first.otpChallengeId === null) throw first;
        let challengeId = first.otpChallengeId;
        setError(null);
        setResent(false);
        for (;;) {
          const answer = await ask(challengeId);
          if (answer === 'cancel') {
            setChallenge(null);
            throw new ApiError(400, { messageKey: 'otp.cancelled' }, undefined);
          }
          if (answer === 'resend') {
            setError(null);
            setResent(false);
            setResending(true);
            try {
              // Asked again without a code: the server answers with a new challenge (or, should the
              // action no longer need one, simply does it).
              const result = await call();
              setChallenge(null);
              return result;
            } catch (again) {
              if (again instanceof ApiError && again.otpChallengeId !== null) {
                challengeId = again.otpChallengeId;
                setResent(true);
              } else {
                // A limit (codes an hour) or another refusal: said in the dialog; the code already
                // sent still works.
                setError(again instanceof ApiError ? again : new ApiError(0, {}, undefined));
              }
              continue;
            } finally {
              setResending(false);
            }
          }
          try {
            const result = await call({ challengeId, code: answer.code });
            setChallenge(null);
            setError(null);
            return result;
          } catch (retry) {
            if (retry instanceof ApiError && retry.code === 'AUTH-400-OTP') {
              setResent(false);
              setError(retry);
              continue;
            }
            setChallenge(null);
            throw retry;
          }
        }
      }
    },
    [ask],
  );

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const code = String(new FormData(event.currentTarget).get('code') ?? '');
    pending.current?.resolve({ code });
  }

  const value = useMemo(() => run, [run]);
  return (
    <GovernedContext.Provider value={value}>
      {children}
      {challenge !== null && (
        <div className="fl-modal-backdrop" role="presentation">
          <form
            ref={dialog}
            className="fl-modal"
            role="dialog"
            aria-modal="true"
            aria-labelledby="otp-title"
            onSubmit={submit}
          >
            <h2 id="otp-title">{t('otp.title')}</h2>
            <p>{t('otp.sent')}</p>
            <label className="fl-field">
              {t('otp.code')}
              <input
                name="code"
                inputMode="numeric"
                autoComplete="one-time-code"
                maxLength={6}
                pattern="[0-9]{6}"
                data-autofocus
                required
              />
            </label>
            {demo && <DemoOtpCode />}
            {resent && !error && (
              <p className="fl-hint" role="status">
                {t('otp.resent')}
              </p>
            )}
            {error && (
              <p className="fl-error" role="alert">
                {t(error.messageKey, error.args)}
              </p>
            )}
            <div className="fl-actions">
              <button type="button" className="btn" onClick={() => pending.current?.resolve('cancel')}>
                {t('common.cancel')}
              </button>
              <button
                type="button"
                className="btn"
                disabled={resending}
                onClick={() => pending.current?.resolve('resend')}
              >
                {t('otp.resend')}
              </button>
              <button type="submit" className="btn pri">
                {t('otp.verify')}
              </button>
            </div>
          </form>
        </div>
      )}
    </GovernedContext.Provider>
  );
}
