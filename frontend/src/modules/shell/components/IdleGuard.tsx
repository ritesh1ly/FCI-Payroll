'use client';

import { useEffect, useRef, useState } from 'react';
import { onServerActivity } from '@/shared/api';
import { useI18n } from '@/shared/i18n';
import { useDialogFocus } from '@/shared/ui';

/** Seconds before the idle timeout at which the user is asked to show they are still there. */
export const IDLE_WARNING_SECONDS = 30;
/** User activity keeps the server session alive at most this often (one request a minute). */
const KEEP_ALIVE_MS = 60_000;
const ACTIVITY_EVENTS = ['pointerdown', 'keydown', 'wheel', 'touchstart'] as const;

/**
 * Inactivity sign-out with a warning (SRS-G-48 / SRS-SEC-14: idle logout after an on-screen warning).
 * Activity is the user's own input on the page or any answer from the server; while the user works
 * without calling the server, the session is kept alive at most once a minute. {@link
 * IDLE_WARNING_SECONDS} before the idle timeout a dialog counts down and asks the user to press "Stay
 * signed in": from then on only that button counts (a mouse moving past does not). At zero the user
 * is signed out and the session cleared.
 */
export function IdleGuard({
  idleTimeoutSeconds,
  onKeepAlive,
  onExpire,
}: {
  idleTimeoutSeconds: number;
  onKeepAlive: () => Promise<unknown>;
  onExpire: () => void;
}) {
  const { t } = useI18n();
  const lastActivity = useRef(Date.now());
  const lastServerCall = useRef(Date.now());
  const warning = useRef(false);
  const expired = useRef(false);
  const [secondsLeft, setSecondsLeft] = useState<number | null>(null);
  const dialog = useDialogFocus<HTMLDivElement>(secondsLeft !== null);

  useEffect(() => {
    onServerActivity(() => {
      lastServerCall.current = Date.now();
      if (!warning.current) lastActivity.current = Date.now();
    });
    return () => onServerActivity(null);
  }, []);

  useEffect(() => {
    const onInput = () => {
      if (warning.current) return;
      lastActivity.current = Date.now();
      if (Date.now() - lastServerCall.current > KEEP_ALIVE_MS) void onKeepAlive();
    };
    ACTIVITY_EVENTS.forEach((e) => window.addEventListener(e, onInput, { passive: true }));
    return () => ACTIVITY_EVENTS.forEach((e) => window.removeEventListener(e, onInput));
  }, [onKeepAlive]);

  useEffect(() => {
    const tick = () => {
      if (expired.current) return;
      const remaining = Math.ceil(idleTimeoutSeconds - (Date.now() - lastActivity.current) / 1000);
      if (remaining <= 0) {
        expired.current = true;
        warning.current = false;
        setSecondsLeft(null);
        onExpire();
      } else if (remaining <= IDLE_WARNING_SECONDS) {
        warning.current = true;
        setSecondsLeft(remaining);
      } else if (warning.current) {
        warning.current = false;
        setSecondsLeft(null);
      }
    };
    const id = window.setInterval(tick, 1000);
    return () => window.clearInterval(id);
  }, [idleTimeoutSeconds, onExpire]);

  async function stay() {
    warning.current = false;
    lastActivity.current = Date.now();
    setSecondsLeft(null);
    await onKeepAlive();
  }

  if (secondsLeft === null) return null;
  return (
    <div className="fl-modal-backdrop" role="presentation">
      <div
        ref={dialog}
        className="fl-modal"
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="idle-title"
        aria-describedby="idle-text"
      >
        <h2 id="idle-title">{t('idle.title')}</h2>
        <p id="idle-text">{t('idle.text')}</p>
        <p className="fl-countdown" role="timer" aria-live="polite">
          {t('idle.countdown', [secondsLeft])}
        </p>
        <div className="fl-actions">
          <button type="button" className="btn pri" data-autofocus onClick={() => void stay()}>
            {t('idle.stay')}
          </button>
        </div>
      </div>
    </div>
  );
}
