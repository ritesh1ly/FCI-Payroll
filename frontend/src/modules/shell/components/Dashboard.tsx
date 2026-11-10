'use client';

import { FileText, Lock, ShieldCheck } from 'lucide-react';
import { useI18n } from '@/shared/i18n';
import { can, type Me, type MenuItem, periodLabel } from '@/shared/session';
import { dateTime } from '@/shared/ui';
import { needsStepUp } from '@/modules/iam';
import { StaffNotices } from '@/modules/eseva';
import { CycleStatusHome } from '@/modules/jobs';
import { CurrentCircularsHome } from '@/modules/configuration';
import { SCREENS } from './screenRegistry';

/**
 * The workspace home after sign-in: who and where the session acts (role, office, payroll period),
 * when it signed in last and when it ends, the state of the OTP step-up of its sensitive screens
 * (SRS-G-48, Module 02 D-2), and a tile per screen the role may open (sensitive ones marked 🔒).
 * No payroll data is shown here, so it never needs a step-up itself. A role with D-01 VIEW also sees
 * the cycle status of its scope above the cards (plan 05 D-20); members and the other roles do not.
 */
export function Dashboard({
  me,
  items,
  onOpen,
  onVerify,
}: {
  me: Me;
  items: MenuItem[];
  onOpen: (code: string) => void;
  onVerify: () => void;
}) {
  const { t, locale } = useI18n();
  const role = me.role ? t(`role.${me.role}`) : (me.roleName ?? '');
  const sensitive = new Set(me.sensitiveScreens);
  const stepUpDue = me.sensitiveScreens.length > 0 && needsStepUp(me, me.sensitiveScreens[0] ?? null);

  return (
    <section className="dash" aria-labelledby="dash-title">
      <h1 id="dash-title">{t('dash.title')}</h1>
      <p className="dash-welcome">{t('dash.welcome', [me.displayName])}</p>
      {can(me, 'D-01', 'VIEW') && <CycleStatusHome />}
      {me.role && me.role !== 'EMPLOYEE_ESEVA' && <StaffNotices />}
      {/* SRS-G-38: the circulars in force for the user's office, for a role that may view M-25. */}
      {can(me, 'M-25', 'VIEW') && <CurrentCircularsHome />}

      <div className="dash-cards">
        <div className="dash-card">
          <span className="m">{t('dash.workingAs')}</span>
          <b>{role}</b>
          <span>{me.location ? me.location.label : t('scope.noOffice')}</span>
        </div>
        <div className="dash-card">
          <span className="m">{t('dash.period')}</span>
          <b>{periodLabel(me.period, locale)}</b>
        </div>
        <div className="dash-card">
          <span className="m">{t('dash.lastSignIn')}</span>
          <b>{dateTime(me.lastLoginAt)}</b>
          <span>{t('dash.sessionEnds', [dateTime(me.session?.absoluteExpiresAt)])}</span>
        </div>
        <div className={`dash-card${stepUpDue ? ' warn' : ''}`}>
          <span className="m">{t('dash.otp')}</span>
          {me.sensitiveScreens.length === 0 ? (
            <b>{t('dash.otpNotNeeded')}</b>
          ) : stepUpDue ? (
            <>
              <b>{t('dash.otpDue')}</b>
              <button type="button" className="btn pri" onClick={onVerify}>
                <ShieldCheck size={14} aria-hidden="true" /> {t('dash.otpVerify')}
              </button>
            </>
          ) : (
            <b>{t('dash.otpValidUntil', [dateTime(me.stepUpValidUntil)])}</b>
          )}
        </div>
      </div>

      <h2 className="dash-sub">{t('dash.screens')}</h2>
      {items.length === 0 ? (
        <p>{t('nav.noScreens')}</p>
      ) : (
        <ul className="dash-tiles">
          {items
            // One tile per screen: a screen drawn under several sidebar groups (OD-121) is one screen here.
            .filter((item, n, all) => all.findIndex((x) => x.code === item.code) === n)
            .map((item) => {
              const Icon = SCREENS[item.code]?.icon ?? FileText;
              return (
                <li key={item.code}>
                  <button type="button" className="dash-tile" onClick={() => onOpen(item.code)}>
                    <Icon size={18} aria-hidden="true" />
                    <span>{t(item.labelKey)}</span>
                    <span className="tag">{item.code}</span>
                    {sensitive.has(item.code) && (
                      <Lock size={14} aria-label={t('dash.sensitive')} className="dash-lock" />
                    )}
                  </button>
                </li>
              );
            })}
        </ul>
      )}
    </section>
  );
}
