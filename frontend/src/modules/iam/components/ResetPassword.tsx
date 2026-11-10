'use client';

import { ArrowRight, Check } from 'lucide-react';
import { type FormEvent, useState } from 'react';
import { ApiError } from '@/shared/api';
import { useI18n } from '@/shared/i18n';
import { L, formText } from '@/shared/ui';
import { confirmationDiffers } from '../business/passwordRules';
import { useRequestReset, useResetPassword } from '../orchestration/authQueries';

/**
 * Forgot password inside the live help dialog: an OTP to the registered e-mail, then OTP + new
 * password. The first step never reveals whether the login ID exists.
 */
export function ResetPassword({ onDone }: { onDone: () => void }) {
  const { t } = useI18n();
  const [step, setStep] = useState<'request' | 'verify' | 'done'>('request');
  const [loginId, setLoginId] = useState('');
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);
  const requestReset = useRequestReset();
  const resetPassword = useResetPassword();

  async function run(action: () => Promise<void>) {
    setBusy(true);
    setError(null);
    try {
      await action();
    } catch (cause) {
      setError(cause instanceof ApiError ? cause : new ApiError(0, {}, undefined));
    } finally {
      setBusy(false);
    }
  }

  function request(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    void run(async () => {
      await requestReset.mutateAsync(loginId);
      setStep('verify');
    });
  }

  function verify(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const password = formText(form, 'newPassword');
    if (confirmationDiffers(password, formText(form, 'confirmPassword'))) {
      setError(new ApiError(400, { messageKey: 'reset.mismatch' }, undefined));
      return;
    }
    void run(async () => {
      await resetPassword.mutateAsync({
        loginId,
        code: formText(form, 'code'),
        newPassword: password,
      });
      setStep('done');
    });
  }

  if (step === 'done') {
    return (
      <>
        <p>{t('reset.done')}</p>
        <button className={L.submit} onClick={onDone}>
          {t('login.gotIt')} <Check size={18} />
        </button>
      </>
    );
  }

  // Keyed forms: without a key React reuses the login ID input as the OTP input, value included.
  return step === 'request' ? (
    <form key="request" method="post" onSubmit={request} noValidate>
      <p>{t('reset.intro')}</p>
      <label className={L.field}>
        {t('login.userId')}
        <div className={L.inputWrap}>
          <input
            name="loginId"
            autoComplete="username"
            value={loginId}
            onChange={(e) => setLoginId(e.target.value)}
            placeholder={t('login.userIdPlaceholder')}
          />
        </div>
      </label>
      {error && (
        <p className="fl-error" role="alert">
          {t(error.messageKey, error.args)}
        </p>
      )}
      <button className={L.submit} type="submit" disabled={busy || loginId.trim() === ''}>
        {t('reset.sendOtp')} <ArrowRight size={18} />
      </button>
    </form>
  ) : (
    <form key="verify" method="post" onSubmit={verify} noValidate>
      <p>{t('reset.otpSent')}</p>
      <label className={L.field}>
        {t('otp.code')}
        <div className={L.inputWrap}>
          <input
            name="code"
            inputMode="numeric"
            autoComplete="one-time-code"
            maxLength={6}
            pattern="[0-9]{6}"
          />
        </div>
      </label>
      <label className={L.field}>
        {t('password.new')}
        <div className={L.inputWrap}>
          <input name="newPassword" type="password" maxLength={128} autoComplete="new-password" />
        </div>
      </label>
      <label className={L.field}>
        {t('password.confirm')}
        <div className={L.inputWrap}>
          <input name="confirmPassword" type="password" maxLength={128} autoComplete="new-password" />
        </div>
      </label>
      <p className="fl-hint">{t('password.rules')}</p>
      {error && (
        <p className="fl-error" role="alert">
          {t(error.messageKey, error.args)}
        </p>
      )}
      <button className={L.submit} type="submit" disabled={busy}>
        {t('reset.submit')} <ArrowRight size={18} />
      </button>
    </form>
  );
}
