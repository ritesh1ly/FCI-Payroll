'use client';

import { Check, Headphones, ShieldCheck, X } from 'lucide-react';
import { useRouter } from 'next/navigation';
import { type FormEvent, type KeyboardEvent, useEffect, useRef, useState } from 'react';
import type { ApiError } from '@/shared/api';
import { L, useDialogFocus } from '@/shared/ui';
import { LanguageToggle, useI18n } from '@/shared/i18n';
import type { OtpSent, SignedIn } from '../api/authApi';
import {
  ID_TYPE,
  identifierProblem,
  passwordProblem,
  readRemembered,
  rememberCookie,
  type SignInMethod,
} from '../business/loginRules';
import { asApiError, useLogin, useLoginMobile, useLoginOptions } from '../orchestration/authQueries';
import { LoginHero } from './LoginHero';
import { LoginOtpStep } from './LoginOtpStep';
import { ResetPassword } from './ResetPassword';

/** The tabs; the registered mobile is its own mode, opened by "Sign in with OTP on registered mobile". */
const METHODS: SignInMethod[] = ['email', 'employee'];

/** The user ID field of each method: its label and placeholder. */
const IDENTIFIER: Record<SignInMethod, { label: string; placeholder: string }> = {
  email: { label: 'login.email', placeholder: 'login.emailPlaceholder' },
  employee: { label: 'login.employeeId', placeholder: 'login.employeeIdPlaceholder' },
  mobile: { label: 'login.mobile', placeholder: 'login.mobilePlaceholder' },
};

/**
 * The FCI Lekha sign-in page (X-08), as the login wireframe (payroll-login-wireframe_1.html; owner
 * 2026-10-30: no deviation, no logo or other image): the brand story on the left, the sign-in card
 * on the right, with the wireframe's own icons and fonts. Sign in with the official e-mail or the Employee ID (EIS
 * number; OD-37) and the password; then, where the server asks for it (SRS-G-48, OD-36), the
 * six-digit OTP sent to the registered mobile (LoginOtpStep). "Sign in with OTP on registered mobile"
 * (OD-41) is the passwordless way: the registered number, then its code; no password and no
 * Forgot password there. Inline validation, show / hide and
 * a Caps Lock hint on the password, "Remember this device" (the method and the user ID only;
 * OD-38), forgotten-password reset by OTP, help and privacy notes, EN | हिं. The office and the
 * acting role are not chosen here: the workspace starts in the user's previous scope and the
 * top-bar selectors switch it. No credentials are ever shown, not even the demo ones.
 */
export function LoginPage() {
  const router = useRouter();
  const { t, locale, setLocale } = useI18n();
  const options = useLoginOptions();
  const login = useLogin();
  const loginMobile = useLoginMobile();
  const [method, setMethod] = useState<SignInMethod>('email');
  const [identifier, setIdentifier] = useState('');
  const [password, setPassword] = useState('');
  const [remember, setRemember] = useState(false);
  const [identifierMsg, setIdentifierMsg] = useState<string | null>(null);
  const [passwordMsg, setPasswordMsg] = useState<string | null>(null);
  const [showPassword, setShowPassword] = useState(false);
  const [capsLock, setCapsLock] = useState(false);
  const [sent, setSent] = useState<OtpSent | null>(null);
  const [help, setHelp] = useState<'support' | 'reset' | 'privacy' | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);
  const identifierField = useRef<HTMLInputElement>(null);
  const passwordField = useRef<HTMLInputElement>(null);
  const tabs = useRef<(HTMLButtonElement | null)[]>([]);
  const dialog = useDialogFocus<HTMLElement>(help !== null);
  // Signed out by the inactivity guard (/?signedOut=idle) or for an access offence
  // (/?signedOut=offence, OD-130): say why, once.
  const [idleNotice, setIdleNotice] = useState(false);
  const [offenceNotice, setOffenceNotice] = useState(false);
  // Until React has hydrated, a click would submit the form natively and put the password in the
  // URL (history, proxy and access logs: OWASP ASVS V3.1, WSTG-INFO-05): sign-in waits for it.
  const [hydrated, setHydrated] = useState(false);
  useEffect(() => {
    const signedOut = new URLSearchParams(window.location.search).get('signedOut');
    setIdleNotice(signedOut === 'idle');
    setOffenceNotice(signedOut === 'offence');
    const remembered = readRemembered(document.cookie);
    if (remembered) {
      setMethod(remembered.method);
      setIdentifier(remembered.identifier);
      setRemember(true);
    }
    setHydrated(true);
  }, []);

  /** Another method: the user ID is typed afresh. A click moves on to the field; an arrow key stays on the tab. */
  function choose(next: SignInMethod, toField: boolean) {
    if (next === method) return;
    setMethod(next);
    setIdentifier('');
    setIdentifierMsg(null);
    setError(null);
    if (toField) identifierField.current?.focus();
  }

  /** Arrow keys move between the tabs (WAI-ARIA tabs pattern); with two, either key goes to the other. */
  function tabKey(event: KeyboardEvent<HTMLButtonElement>, index: number) {
    if (event.key !== 'ArrowRight' && event.key !== 'ArrowLeft') return;
    event.preventDefault();
    tabs.current[1 - index]?.focus();
    choose(index === 0 ? 'employee' : 'email', false);
  }

  function capsFrom(event: KeyboardEvent<HTMLInputElement>) {
    setCapsLock(event.getModifierState('CapsLock'));
  }

  /** The session is open: the language and the remembered sign-in are kept, the workspace opens. */
  async function signedIn(answer: SignedIn) {
    const secure = window.location.protocol === 'https:';
    document.cookie = rememberCookie(remember ? { method, identifier } : null, secure);
    // The language on the sign-in page is the user's latest choice: it becomes the stored
    // preference, so the workspace opens in it (otherwise /auth/me would restore the old one).
    await setLocale(locale);
    router.push(answer.route);
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const idWrong = identifierProblem(method, identifier);
    // OD-41: the mobile sign-in has no password; its code is the proof.
    const pwWrong = method === 'mobile' ? null : passwordProblem(password);
    setIdentifierMsg(idWrong);
    setPasswordMsg(pwWrong);
    if (idWrong || pwWrong) {
      (idWrong ? identifierField : passwordField).current?.focus();
      return;
    }
    setBusy(true);
    setError(null);
    try {
      if (method === 'mobile') {
        setSent(await loginMobile.mutateAsync(identifier.trim()));
        return;
      }
      const answer = await login.mutateAsync({
        loginId: identifier.trim(),
        password,
        idType: ID_TYPE[method],
      });
      if (answer.otpRequired) {
        // The password has done its part: it does not stay in the page.
        setPassword('');
        setSent(answer);
      } else {
        await signedIn(answer);
      }
    } catch (cause) {
      setError(asApiError(cause));
    } finally {
      setBusy(false);
    }
  }

  const email = method === 'email';
  const mobile = method === 'mobile';

  return (
    <main className="wf lx-page">
      <div className="lx-blob" aria-hidden="true" />
      <svg className="lx-leaves" viewBox="0 0 300 400" aria-hidden="true">
        <g fill="#9FCFAA">
          <path d="M200 20c40 30 50 80 20 120-30-30-40-80-20-120z" />
          <path d="M250 120c30 30 30 80 0 110-25-30-25-80 0-110z" />
          <path d="M150 140c35 20 50 60 30 100-30-20-45-60-30-100z" />
          <path d="M230 250c30 25 35 70 10 100-25-25-30-70-10-100z" />
          <path d="M120 40c30 25 35 65 15 95-25-25-30-65-15-95z" />
        </g>
        <path d="M210 0 C215 120 240 240 260 400" stroke="#8CC39A" strokeWidth="3" fill="none" />
      </svg>
      <div className="lx-grid">
        <LoginHero />

        <section className="lx-card lx-rv lx-d3" aria-labelledby="lx-title">
          <div className="lx-lang">
            <LanguageToggle />
          </div>
          <h2 id="lx-title" className="lx-sr">
            {t('login.signIn')}
          </h2>
          <div className="lx-welcome">{t('login.welcome')}</div>
          <div className="lx-lname">
            {t('brand.fci')}
            <span> {t('brand.lekha')}</span>
          </div>
          <div className="lx-sys">{t('login.system')}</div>
          <div className="lx-rule" />

          {sent ? (
            <LoginOtpStep
              sent={sent}
              demo={options.data?.demo === true}
              onSignedIn={signedIn}
              onBack={() => {
                setSent(null);
                setError(null);
              }}
            />
          ) : (
            <>
              {mobile ? (
                <div className="lx-mode">
                  <h3>
                    <span aria-hidden="true">📱</span> {t('login.otpOption')}
                  </h3>
                  <button type="button" className="lx-link" onClick={() => choose('email', true)}>
                    {t('login.mobileBack')}
                  </button>
                </div>
              ) : (
                <div className="lx-tabs" role="tablist" aria-label={t('login.method')}>
                  {METHODS.map((m, i) => (
                    <button
                      key={m}
                      ref={(el) => {
                        tabs.current[i] = el;
                      }}
                      type="button"
                      role="tab"
                      id={`lx-tab-${m}`}
                      aria-selected={method === m}
                      aria-controls="lx-form"
                      tabIndex={method === m ? 0 : -1}
                      className={method === m ? 'on' : ''}
                      onClick={() => choose(m, true)}
                      onKeyDown={(e) => tabKey(e, i)}
                    >
                      {t(m === 'email' ? 'login.method.email' : 'login.method.employee')}
                    </button>
                  ))}
                </div>
              )}

              {/* method="post": should a native submit ever happen, the credentials go in the body. */}
              <form
                id="lx-form"
                role={mobile ? undefined : 'tabpanel'}
                aria-labelledby={mobile ? undefined : `lx-tab-${method}`}
                method="post"
                onSubmit={submit}
                noValidate
              >
                <div className="lx-field">
                  <label htmlFor="lx-user">{t(IDENTIFIER[method].label)}</label>
                  <div className={`lx-ctl ${identifierMsg ? 'err' : ''}`}>
                    {email ? <MailIcon /> : mobile ? <PhoneIcon /> : <IdIcon />}
                    <input
                      ref={identifierField}
                      id="lx-user"
                      name="username"
                      type={email ? 'email' : mobile ? 'tel' : 'text'}
                      inputMode={mobile ? 'tel' : undefined}
                      autoComplete={mobile ? 'tel' : 'username'}
                      spellCheck={false}
                      placeholder={t(IDENTIFIER[method].placeholder)}
                      value={identifier}
                      maxLength={150}
                      onChange={(e) => {
                        setIdentifier(e.target.value);
                        if (identifierMsg) setIdentifierMsg(identifierProblem(method, e.target.value));
                      }}
                      onBlur={() => identifier && setIdentifierMsg(identifierProblem(method, identifier))}
                      aria-invalid={identifierMsg !== null}
                      aria-describedby="lx-user-msg"
                    />
                  </div>
                  <div className="lx-msg" id="lx-user-msg" aria-live="polite">
                    {identifierMsg && t(identifierMsg)}
                  </div>
                  {mobile && !identifierMsg && (
                    <p className="lx-hint lx-mobile-hint">{t('login.mobileHint')}</p>
                  )}
                </div>

                {!mobile && (
                  <>
                    <div className="lx-field">
                      <label htmlFor="lx-pw">{t('login.password')}</label>
                      <div className={`lx-ctl ${passwordMsg ? 'err' : ''}`}>
                        <LockIcon />
                        <input
                          ref={passwordField}
                          id="lx-pw"
                          name="password"
                          type={showPassword ? 'text' : 'password'}
                          autoComplete="current-password"
                          placeholder={t('login.passwordPlaceholder')}
                          value={password}
                          maxLength={128}
                          onChange={(e) => {
                            setPassword(e.target.value);
                            if (passwordMsg) setPasswordMsg(passwordProblem(e.target.value));
                          }}
                          onKeyDown={capsFrom}
                          onKeyUp={capsFrom}
                          onBlur={() => setCapsLock(false)}
                          aria-invalid={passwordMsg !== null}
                          aria-describedby="lx-pw-msg"
                        />
                        <button
                          type="button"
                          className="lx-eye"
                          aria-label={showPassword ? t('login.hidePassword') : t('login.showPassword')}
                          aria-pressed={showPassword}
                          onClick={() => setShowPassword(!showPassword)}
                        >
                          <EyeIcon />
                        </button>
                      </div>
                      {capsLock && (
                        <div className="lx-caps" role="status">
                          <span aria-hidden="true">⚠</span> <span>{t('login.capsLock')}</span>
                        </div>
                      )}
                      <div className="lx-msg" id="lx-pw-msg" aria-live="polite">
                        {passwordMsg && t(passwordMsg)}
                      </div>
                    </div>
                  </>
                )}
                <div className="lx-row">
                  <label title={t('login.rememberHint')}>
                    <input
                      type="checkbox"
                      checked={remember}
                      onChange={(e) => setRemember(e.target.checked)}
                    />
                    {t('login.remember')}
                  </label>
                  {!mobile && (
                    <button type="button" className="lx-link" onClick={() => setHelp('reset')}>
                      {t('login.forgotPassword')}
                    </button>
                  )}
                </div>
                {remember && <p className="lx-hint">{t('login.rememberHint')}</p>}

                {offenceNotice && !error && (
                  <p className="fl-error" role="alert">
                    {t('error.AUTH-401-OFFENCE')}
                  </p>
                )}
                {idleNotice && !error && (
                  <p className="fl-hint" role="status">
                    {t('idle.signedOut')}
                  </p>
                )}
                {error && (
                  <p className="fl-error" role="alert">
                    {t(error.messageKey, error.args)}
                  </p>
                )}
                <button className="lx-signin" type="submit" disabled={busy || !hydrated} aria-busy={busy}>
                  <span className="lbl">
                    {busy ? t('login.signingIn') : t(mobile ? 'login.sendOtp' : 'login.submit')}
                  </span>
                  {busy ? <span className="lx-spin" aria-hidden="true" /> : <ArrowIcon />}
                </button>
                {!mobile && (
                  <>
                    <div className="lx-alt">{t('login.or')}</div>
                    <button className="lx-otp" type="button" onClick={() => choose('mobile', true)}>
                      📱 {t('login.otpOption')}
                    </button>
                  </>
                )}
                {options.isError && (
                  <p className="fl-error" role="alert">
                    {t('error.network')}
                  </p>
                )}
              </form>
            </>
          )}

          <ul className="lx-trust" aria-label={t('login.trustLabel')}>
            <li>{t('login.trust.secure')}</li>
            <li>{t('login.trust.reliable')}</li>
            <li>{t('login.trust.compliant')}</li>
          </ul>
          <div className="lx-foot">
            <button type="button" onClick={() => setHelp('support')}>
              {t('login.helpLine')}
            </button>
            <button type="button" onClick={() => setHelp('privacy')}>
              {t('login.privacy')}
            </button>
          </div>
        </section>
      </div>
      <div className="lx-waves" aria-hidden="true">
        <svg viewBox="0 0 1440 150" preserveAspectRatio="none">
          <path d="M0 70 C300 140 520 20 820 70 S1260 120 1440 40 V150 H0z" fill="#C9E6D2" opacity=".7" />
          <path d="M0 105 C260 60 520 150 860 100 S1260 50 1440 80 V150 H0z" fill="#D6A531" />
          <path d="M0 118 C280 80 560 160 880 115 S1280 75 1440 100 V150 H0z" fill="#16284A" />
          <path d="M0 125 C200 100 380 150 560 140 V150 H0z" fill="#17693A" />
        </svg>
      </div>
      {help && (
        <div
          className={L.modalBackdrop}
          role="presentation"
          onClick={(e) => {
            if (e.target === e.currentTarget) setHelp(null);
          }}
          onKeyDown={(e) => {
            if (e.key === 'Escape') setHelp(null);
          }}
        >
          <section
            ref={dialog}
            className={`card ${L.modal}`}
            role="dialog"
            aria-modal="true"
            aria-labelledby="help-title"
          >
            <button
              data-autofocus
              className={L.close}
              aria-label={t('login.closeHelp')}
              onClick={() => setHelp(null)}
            >
              <X size={20} />
            </button>
            {help === 'privacy' ? <ShieldCheck size={28} /> : <Headphones size={28} />}
            <h2 id="help-title">
              {t(
                help === 'reset'
                  ? 'login.resetTitle'
                  : help === 'privacy'
                    ? 'login.privacyTitle'
                    : 'login.helpTitle',
              )}
            </h2>
            {help === 'reset' ? (
              <ResetPassword onDone={() => setHelp(null)} />
            ) : (
              <>
                <p>{t(help === 'privacy' ? 'login.privacyText' : 'login.helpText')}</p>
                {help === 'support' && (
                  <div className={L.helpContact}>
                    {t('login.helpdesk')} <strong>{t('login.helpdeskExtension')}</strong>
                  </div>
                )}
                <button className={L.submit} onClick={() => setHelp(null)}>
                  {t('login.gotIt')} <Check size={18} />
                </button>
              </>
            )}
          </section>
        </div>
      )}
    </main>
  );
}

/* The wireframe's own field icons (stroke 1.8, 22 px, the field's grey). */
function MailIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
      <rect x="3" y="5" width="18" height="14" rx="2" />
      <path d="m3.5 6 8.5 7 8.5-7" />
    </svg>
  );
}

function IdIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
      <rect x="3" y="5" width="18" height="14" rx="2" />
      <circle cx="9" cy="11" r="2.2" />
      <path d="M5.5 17c.6-2 2-3 3.5-3s2.9 1 3.5 3M14.5 10h4M14.5 13.5h3" />
    </svg>
  );
}

/** The registered mobile (the wireframe's 📱 of its OTP button, as a field icon). */
function PhoneIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
      <rect x="6.5" y="2.5" width="11" height="19" rx="2.2" />
      <path d="M10.5 18.5h3" />
    </svg>
  );
}

function LockIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
      <rect x="4.5" y="10" width="15" height="11" rx="2" />
      <path d="M8 10V7a4 4 0 0 1 8 0v3" />
      <circle cx="12" cy="15.5" r="1.3" fill="currentColor" />
    </svg>
  );
}

function EyeIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
      <path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z" />
      <circle cx="12" cy="12" r="3" />
    </svg>
  );
}

function ArrowIcon() {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2.2"
      strokeLinecap="round"
      aria-hidden="true"
    >
      <path d="M4 12h15M13 6l6 6-6 6" />
    </svg>
  );
}
