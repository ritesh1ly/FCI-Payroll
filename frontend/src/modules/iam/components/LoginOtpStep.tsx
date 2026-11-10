'use client';

import { ArrowLeft, RotateCw, ShieldCheck } from 'lucide-react';
import { type FormEvent, useEffect, useRef, useState } from 'react';
import type { ApiError } from '@/shared/api';
import { useI18n } from '@/shared/i18n';
import { DemoOtpCode } from '@/shared/ui';
import type { OtpSent, SignedIn } from '../api/authApi';
import { countdown, otpDigits, otpProblem } from '../business/loginRules';
import { asApiError, useLoginOtp, useResendLoginOtp } from '../orchestration/authQueries';

/**
 * X-08 step 2 (SRS-G-48, SRS-G-21; OD-36): the six-digit OTP sent to the registered mobile (or, for
 * a user without one, the registered e-mail). Shows where it went (masked) and how long it stays
 * valid; Verify opens the session; Resend sends a new code (the earlier one stops working); "Use
 * another account" goes back to the password. A wrong code is cleared for another try; the server
 * counts the attempts and locks the account as for passwords (SRS-G-49).
 */
export function LoginOtpStep({
  sent: initial,
  onSignedIn,
  onBack,
  demo = false,
}: {
  sent: OtpSent;
  onSignedIn: (answer: SignedIn) => Promise<void>;
  onBack: () => void;
  /** OD-146: a demo stack offers the pending sign-in's own code. */
  demo?: boolean;
}) {
  const { t } = useI18n();
  const verify = useLoginOtp();
  const resend = useResendLoginOtp();
  const [sent, setSent] = useState(initial);
  const [code, setCode] = useState('');
  const [problem, setProblem] = useState<string | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [resent, setResent] = useState(false);
  const [busy, setBusy] = useState(false);
  const field = useRef<HTMLInputElement>(null);
  const [deadline, setDeadline] = useState(() => Date.now() + initial.validSeconds * 1000);
  const [left, setLeft] = useState(initial.validSeconds);

  useEffect(() => {
    field.current?.focus();
    const tick = () => setLeft(Math.max(0, Math.round((deadline - Date.now()) / 1000)));
    tick();
    const timer = window.setInterval(tick, 1000);
    return () => window.clearInterval(timer);
  }, [deadline]);

  const expired = left <= 0;

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const wrong = otpProblem(code);
    setProblem(wrong);
    setResent(false);
    if (wrong) {
      field.current?.focus();
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await onSignedIn(await verify.mutateAsync(code));
    } catch (cause) {
      setError(asApiError(cause));
      setCode('');
      field.current?.focus();
    } finally {
      setBusy(false);
    }
  }

  async function sendAgain() {
    setError(null);
    setProblem(null);
    setResent(false);
    try {
      const again = await resend.mutateAsync();
      setSent(again);
      setDeadline(Date.now() + again.validSeconds * 1000);
      setCode('');
      setResent(true);
    } catch (cause) {
      setError(asApiError(cause));
    }
  }

  return (
    <form className="lx-otp-step" method="post" onSubmit={submit} noValidate>
      <div className="lx-otp-head">
        <ShieldCheck size={26} aria-hidden="true" />
        <h3>{t('login.otp.title')}</h3>
      </div>
      <p className="lx-otp-sent">
        {t(sent.channel === 'SMS' ? 'login.otp.sentSms' : 'login.otp.sentEmail', [sent.destination])}
      </p>
      <div className="lx-field">
        <label htmlFor="lx-otp">{t('login.otp.code')}</label>
        <div className={`lx-ctl ${problem ? 'err' : ''}`}>
          <input
            ref={field}
            id="lx-otp"
            name="code"
            className="lx-otp-input"
            value={code}
            onChange={(e) => {
              setCode(otpDigits(e.target.value));
              if (problem) setProblem(null);
            }}
            inputMode="numeric"
            autoComplete="one-time-code"
            pattern="\d{6}"
            maxLength={6}
            aria-invalid={problem !== null}
            aria-describedby="lx-otp-msg lx-otp-timer"
          />
        </div>
        <div className="lx-msg" id="lx-otp-msg" aria-live="polite">
          {problem && t(problem)}
        </div>
        <p className={`lx-timer ${expired ? 'off' : ''}`} id="lx-otp-timer">
          {expired ? t('login.otp.expired') : t('login.otp.validFor', [countdown(left)])}
        </p>
      </div>
      {demo && <DemoOtpCode pendingSignIn />}
      {resent && !error && (
        <p className="lx-notice on" role="status">
          {t('login.otp.resent')}
        </p>
      )}
      {error && (
        <p className="fl-error" role="alert">
          {t(error.messageKey, error.args)}
        </p>
      )}
      <button className="lx-signin" type="submit" disabled={busy || expired} aria-busy={busy}>
        <span className="lbl">{busy ? t('login.otp.verifying') : t('login.otp.verify')}</span>
        {busy && <span className="lx-spin" aria-hidden="true" />}
      </button>
      <div className="lx-otp-actions">
        <button type="button" onClick={onBack}>
          <ArrowLeft size={16} aria-hidden="true" /> {t('login.otp.back')}
        </button>
        <button type="button" onClick={() => void sendAgain()} disabled={resend.isPending}>
          <RotateCw size={16} aria-hidden="true" /> {t('login.otp.resend')}
        </button>
      </div>
    </form>
  );
}
