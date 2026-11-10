'use client';

import { type FormEvent, useState } from 'react';
import { ApiError } from '@/shared/api';
import { useI18n } from '@/shared/i18n';
import { confirmationDiffers } from '../business/passwordRules';
import { useChangePassword } from '../orchestration/authQueries';
import { formText } from '@/shared/ui';

/** Change password (forced at first sign-in or after an administrator reset). History of five. */
export function ChangePassword({
  forced,
  onDone,
  onCancel,
}: {
  forced: boolean;
  onDone: () => void;
  onCancel: () => void;
}) {
  const { t } = useI18n();
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);
  const changePassword = useChangePassword();

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const next = formText(form, 'newPassword');
    if (confirmationDiffers(next, formText(form, 'confirmPassword'))) {
      setError(new ApiError(400, { messageKey: 'reset.mismatch' }, undefined));
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await changePassword.mutateAsync({
        currentPassword: formText(form, 'currentPassword'),
        newPassword: next,
      });
      onDone();
    } catch (cause) {
      setError(cause instanceof ApiError ? cause : new ApiError(0, {}, undefined));
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="card fl-password" method="post" onSubmit={submit} aria-labelledby="password-title">
      <div className="hd">
        <h3 id="password-title">{t('password.title')}</h3>
      </div>
      <div className="fl-form">
        {forced && <p className="fl-hint">{t('password.forced')}</p>}
        <label className="fl-field">
          {t('password.current')}
          <input
            name="currentPassword"
            type="password"
            maxLength={128}
            autoComplete="current-password"
            required
          />
        </label>
        <label className="fl-field">
          {t('password.new')}
          <input name="newPassword" type="password" maxLength={128} autoComplete="new-password" required />
        </label>
        <label className="fl-field">
          {t('password.confirm')}
          <input
            name="confirmPassword"
            type="password"
            maxLength={128}
            autoComplete="new-password"
            required
          />
        </label>
        <p className="fl-hint">{t('password.rules')}</p>
        {error && (
          <p className="fl-error" role="alert">
            {t(error.messageKey, error.args)}
            {error.violations.map((v) => (
              <span key={v.code} className="fl-violation">
                {t(`password.policy.${v.code}`)}
              </span>
            ))}
          </p>
        )}
        <div className="fl-actions">
          <button type="button" className="btn" onClick={onCancel}>
            {forced ? t('account.signOut') : t('common.cancel')}
          </button>
          <button type="submit" className="btn pri" disabled={busy}>
            {t('password.submit')}
          </button>
        </div>
      </div>
    </form>
  );
}
