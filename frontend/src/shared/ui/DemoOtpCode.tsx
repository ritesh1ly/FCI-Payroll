'use client';

import { useState } from 'react';
import { api } from '@/shared/api';
import { useI18n } from '@/shared/i18n';

/** /auth/otp/demo-code and /auth/login/otp/demo-code (OD-146). */
interface DemoCode {
  action: string;
  code: string;
  validSeconds: number;
}

/**
 * The demo OTP code viewer (OD-146), drawn in the OTP prompts of a demo stack only: "Show my demo
 * code" asks the server for the newest code sent to the caller's OWN account (the signed-in user, or
 * this browser's pending sign-in) and shows it here; it is never filled in for the user. The server
 * keeps it in memory only, answers nothing outside a demo stack (404) and has no way to name another
 * user. Rendered by the caller only when the session or the sign-in options say "demo".
 */
export function DemoOtpCode({ pendingSignIn = false }: { pendingSignIn?: boolean }) {
  const { t } = useI18n();
  const [shown, setShown] = useState<DemoCode | null>(null);
  const [missing, setMissing] = useState(false);
  const [busy, setBusy] = useState(false);

  async function reveal() {
    setBusy(true);
    setMissing(false);
    try {
      setShown(
        await api<DemoCode>(pendingSignIn ? '/auth/login/otp/demo-code' : '/auth/otp/demo-code', {
          silent: true,
        }),
      );
    } catch {
      // Off, nothing sent, or expired: the same answer (404).
      setShown(null);
      setMissing(true);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="fl-hint demo-otp" data-testid="demo-otp">
      <button type="button" className="btn sm" onClick={() => void reveal()} disabled={busy}>
        {t('otp.demo.show')}
      </button>{' '}
      {shown && (
        <span role="status">
          {t('otp.demo.code', [shown.code])} <span className="sub2">{t('otp.demo.note')}</span>
        </span>
      )}
      {missing && <span role="status">{t('otp.demo.none')}</span>}
    </div>
  );
}
