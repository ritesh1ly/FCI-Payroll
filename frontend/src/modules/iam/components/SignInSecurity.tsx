'use client';

import { useState } from 'react';
import { ApiError } from '@/shared/api';
import { useI18n } from '@/shared/i18n';
import { useMe } from '@/shared/session';
import { Problem, useToast, withBase } from '@/shared/ui';
import { Panel, Tbl } from '@/shared/ui/wireframe';
import { useChangePasswordOnCard, useOtpGatedActions } from '../orchestration/authQueries';

/**
 * X-08 Sign-in, OTP &amp; Password as Wireframes v6.1 draws it in General (a frozen requirement per OD-121; FRS V0.5
 * §6.7.7.4, §6.4.2.5; SRS-G-21, SRS-G-35): the two sign-in steps (user ID and password, then the OTP sent to the
 * registered contact) as the sign-in page performs them - inside the workspace the session is already signed in, so
 * each drawn button says where it acts; the own password change with OTP (the first press sends the OTP, the second
 * changes the password with the code typed here; every other session ends); the session and account controls (all
 * settings); the eighteen OTP-gated actions (SRS-G-21) with the screens on which each is performed and who enters
 * the OTP, as drawn; and the actions that take no OTP. Login always asks for the OTP (SRS line 905; OD-126).
 */
export function SignInSecurity() {
  const { t } = useI18n();
  const toast = useToast();
  const me = useMe();
  const actions = useOtpGatedActions();
  const changePassword = useChangePasswordOnCard();
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [confirm, setConfirm] = useState('');
  const [otp, setOtp] = useState('');
  const [challenge, setChallenge] = useState<number | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const signedIn = t('x08.why.signedIn', [me.data?.loginId ?? '']);

  async function change() {
    setError(null);
    if (current === '' || next === '' || confirm === '') {
      setError(new ApiError(400, { messageKey: 'x08.fillAll' }, undefined));
      return;
    }
    if (next !== confirm) {
      setError(new ApiError(400, { messageKey: 'reset.mismatch' }, undefined));
      return;
    }
    setBusy(true);
    try {
      await changePassword.mutateAsync({
        currentPassword: current,
        newPassword: next,
        otp:
          challenge !== null && otp.trim() !== '' ? { challengeId: challenge, code: otp.trim() } : undefined,
      });
      toast(t('password.changed'));
      setCurrent('');
      setNext('');
      setConfirm('');
      setOtp('');
      setChallenge(null);
    } catch (cause) {
      // The first press answers 428 with the challenge: the OTP is on its way to the registered contact.
      const id = cause instanceof ApiError ? cause.otpChallengeId : null;
      if (id !== null) {
        setChallenge(id);
        toast(t('x08.otpSent'));
      } else {
        setError(cause);
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="wireframe-body-container">
      <div className="g2">
        <div className="card">
          <div className="hd">
            <h3>{t('x08.step1')}</h3>
            {/* i18n-ignore: requirement references, not labels */}
            <span className="frs">§6.7.7.4</span>
          </div>
          <div className="bd">
            <div className="rf-grid">
              <div className="rf-field span2">
                <label htmlFor="x08-user">{t('x08.userId')}</label>
                <input id="x08-user" defaultValue={me.data?.loginId ?? ''} autoComplete="username" />
              </div>
              <div className="rf-field span2">
                <label htmlFor="x08-password">{t('x08.password')}</label>
                <input id="x08-password" type="password" autoComplete="off" />
              </div>
            </div>
            <div className="toolbar fl-mt-10">
              <button type="button" className="btn pri" data-guard={signedIn} onClick={() => toast(signedIn)}>
                {t('x08.continue')}
              </button>
              <button
                type="button"
                className="btn"
                data-guard={t('x08.why.forgot')}
                onClick={() => toast(t('x08.why.forgot'))}
              >
                {t('x08.forgot')}
              </button>
            </div>
          </div>
        </div>
        <div className="card">
          <div className="hd">
            <h3>{t('x08.step2')}</h3>
            {/* i18n-ignore: requirement references, not labels */}
            <span className="frs">§6.4.2.5</span>
          </div>
          <div className="bd">
            <div className="rf-grid">
              <div className="rf-field span2">
                <label htmlFor="x08-otp">{t('x08.otpLabel')}</label>
                <input
                  id="x08-otp"
                  placeholder="● ● ● ● ● ●"
                  inputMode="numeric"
                  autoComplete="one-time-code"
                />
              </div>
            </div>
            <div className="toolbar fl-mt-10">
              <button type="button" className="btn pri" data-guard={signedIn} onClick={() => toast(signedIn)}>
                {t('x08.verify')}
              </button>
              <button type="button" className="btn" data-guard={signedIn} onClick={() => toast(signedIn)}>
                {t('x08.resend')}
              </button>
            </div>
            <div className="oq fl-mt-10">
              {t('x08.everyLoginLead')} <b>{t('x08.everyLogin')}</b>
              {t('x08.everyLoginTail')}
            </div>
          </div>
        </div>
      </div>
      <div className="g2 fl-mt">
        <div className="card">
          <div className="hd">
            <h3>{t('x08.changeTitle')}</h3>
            {/* i18n-ignore: requirement references, not labels */}
            <span className="frs">§6.4.2.5</span>
          </div>
          <div className="bd">
            <Problem error={error} />
            <div className="rf-grid">
              <div className="rf-field span2">
                <label htmlFor="x08-current">{t('password.current')}</label>
                <input
                  id="x08-current"
                  type="password"
                  autoComplete="current-password"
                  value={current}
                  onChange={(e) => setCurrent(e.target.value)}
                />
              </div>
              <div className="rf-field">
                <label htmlFor="x08-new">{t('password.new')}</label>
                <input
                  id="x08-new"
                  type="password"
                  autoComplete="new-password"
                  value={next}
                  onChange={(e) => setNext(e.target.value)}
                />
              </div>
              <div className="rf-field">
                <label htmlFor="x08-confirm">{t('password.confirm')}</label>
                <input
                  id="x08-confirm"
                  type="password"
                  autoComplete="new-password"
                  value={confirm}
                  onChange={(e) => setConfirm(e.target.value)}
                />
              </div>
              <div className="rf-field span2">
                <label htmlFor="x08-change-otp">{t('x08.otp')}</label>
                <input
                  id="x08-change-otp"
                  placeholder={t('x08.otpPlaceholder')}
                  inputMode="numeric"
                  autoComplete="one-time-code"
                  value={otp}
                  onChange={(e) => setOtp(e.target.value)}
                />
              </div>
            </div>
            {challenge !== null && <p className="fl-hint">{t('x08.enterOtp')}</p>}
            <div className="fl-mt-10">
              <button type="button" className="btn pri" disabled={busy} onClick={() => void change()}>
                {t('x08.changeAct')} <span className="pill p-pur">{t('x08.otpPill')}</span>
              </button>
            </div>
          </div>
        </div>
        <Panel
          title={t('x08.controls')}
          items={[
            {
              icon: '🔑',
              tone: 'info',
              title: t('x08.ctl.policy'),
              src: 'RULE',
              text: t('x08.ctl.policyText'),
            },
            { icon: '🔒', tone: 'warn', title: t('x08.ctl.lock'), src: 'SYS', text: t('x08.ctl.lockText') },
            {
              icon: '1️⃣',
              tone: 'info',
              title: t('x08.ctl.single'),
              src: 'SYS',
              text: t('x08.ctl.singleText'),
            },
            { icon: '⏱', tone: 'info', title: t('x08.ctl.idle'), src: 'RULE', text: t('x08.ctl.idleText') },
            {
              icon: '🛰',
              tone: 'gray',
              title: t('x08.ctl.monitor'),
              src: 'SYS',
              text: t('x08.ctl.monitorText'),
            },
            {
              icon: '🚪',
              tone: 'gray',
              title: t('x08.ctl.leavers'),
              src: 'SYS',
              text: t('x08.ctl.leaversText'),
            },
          ]}
        />
      </div>
      <div className="fl-mt">
        <Problem error={actions.error} />
        <Tbl
          title={t('x08.otpListTitle')}
          cols={[
            { t: t('x08.col.n'), s: 'SYS' },
            { t: t('x08.col.action'), s: 'RULE' },
            { t: t('x08.col.screen'), s: 'SYS' },
            { t: t('x08.col.who'), s: 'RULE' },
          ]}
        >
          {(actions.data ?? []).map((a, i) => (
            <tr key={a.code} data-code={a.code}>
              <td>{i + 1}</td>
              <td>{t(`x08.act.${a.code}`)}</td>
              <td>
                {/* The screens on which the action is performed and its OTP entered, as drawn. */}
                {a.screens.map((s, n) => (
                  <span key={s}>
                    {n > 0 && ', '}
                    <a href={withBase(`/payroll/?s=${s}`)}>{s}</a>
                  </span>
                ))}
              </td>
              <td>{t(`x08.who.${a.code}`)}</td>
            </tr>
          ))}
        </Tbl>
      </div>
      <div className="fl-mt">
        <Panel
          title={t('x08.noOtp')}
          items={[
            { icon: '🙋', tone: 'info', title: t('x08.no.eseva'), src: 'RULE', text: t('x08.no.esevaText') },
            { icon: '🔄', tone: 'info', title: t('x08.no.zone'), src: 'RULE', text: t('x08.no.zoneText') },
            { icon: '🎂', tone: 'info', title: t('x08.no.dob'), src: 'RULE', text: t('x08.no.dobText') },
            {
              icon: '⛔',
              tone: 'info',
              title: t('x08.no.resume'),
              src: 'RULE',
              text: t('x08.no.resumeText'),
            },
            { icon: '⚙️', tone: 'gray', title: t('x08.no.list'), src: 'RULE', text: t('x08.no.listText') },
          ]}
        />
      </div>
    </div>
  );
}
