'use client';

import { Download, FileText, KeyRound, LogOut, Search, WifiOff, X } from 'lucide-react';
import { useRouter, useSearchParams } from 'next/navigation';
import { Suspense, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ApiError, onUnauthorized } from '@/shared/api';
import { LanguageToggle, useI18n } from '@/shared/i18n';
import { useInstallPrompt, useOnline } from '@/shared/pwa';
import {
  can,
  periodLabel,
  reportDeniedScreen,
  type Me,
  type MenuItem,
  useMe,
  useMenu,
} from '@/shared/session';
import { COMPACT_QUERY, ToastProvider, useMediaQuery, useToast } from '@/shared/ui';
import {
  ChangePassword,
  needsStepUp,
  PeriodSwitcher,
  roleInitials,
  ScopeSwitcher,
  useKeepAlive,
  useRefreshSession,
  useSignOut,
  useStepUp,
} from '@/modules/iam';
import { GovernedProvider } from '@/modules/workflow';
import { Dashboard } from './Dashboard';
import { IdleGuard } from './IdleGuard';
import { ScreenView } from './ScreenView';
import { SCREENS } from './screenRegistry';

/**
 * The /payroll workspace in the layout of wireframe v6.1 (FCI_Payroll_Wireframes_v6.1_Dev.html):
 * top bar (brand with the screen code, screen search, 📅 period, 🏢 office, 👤 role, EN | हिं,
 * role avatar), the RBAC-filtered sidebar (role bar, collapsible groups,
 * "hidden by RBAC" note) and the screen. The office and the acting role are switched in the top bar
 * without signing out; the menu, buttons and data scope follow the new scope at once. Route guard
 * and menu come only from /api/auth/me and /api/menu.
 *
 * Home: without a screen (?s=) the workspace shows the dashboard, where every sign-in lands.
 * Sensitive screens (SRS-G-48, Module 02 D-2): a session opening in a sensitive role is asked for the
 * OTP at once; a sensitive screen whose confirmation has expired is asked before it opens; any call
 * the server refuses with AUTH-403-STEP-UP asks and runs again (useStepUp). Idle sign-out with a
 * 30-second warning: IdleGuard.
 *
 * Responsive: below 900 px the sidebar is a drawer (☰, Escape or the scrim closes it, the page behind
 * does not scroll), the period / office / role / language controls move to the top of the drawer
 * (mounted in one place only) and the screen search opens from a 🔎 button over the top bar.
 */
export function Workspace() {
  return (
    <Suspense fallback={null}>
      <ToastProvider>
        <GovernedProvider>
          <WorkspaceShell />
        </GovernedProvider>
      </ToastProvider>
    </Suspense>
  );
}

function WorkspaceShell() {
  const router = useRouter();
  const params = useSearchParams();
  const endSession = useSignOut();
  const refreshSession = useRefreshSession();
  const toast = useToast();
  const { t, adopt, locale } = useI18n();
  const me = useMe();
  const ready = Boolean(me.data) && !me.data?.passwordChangeRequired;
  const menu = useMenu(ready);
  const [navOpen, setNavOpen] = useState(false);
  const [account, setAccount] = useState(false);
  const [changingPassword, setChangingPassword] = useState(false);
  const [closed, setClosed] = useState<Record<string, boolean>>({});
  const [query, setQuery] = useState('');
  const [searchOpen, setSearchOpen] = useState(false);
  const compact = useMediaQuery(COMPACT_QUERY);
  const online = useOnline();
  const installPrompt = useInstallPrompt();
  const searchInput = useRef<HTMLInputElement>(null);
  const ensureStepUp = useStepUp();
  const keepAlive = useKeepAlive();
  const askedAtOpen = useRef<string | null>(null);
  // A step-up the server could not start (the hourly OTP cap, an outage) is shown, never swallowed:
  // otherwise the user waits on the dashboard or the gate with no OTP and no reason.
  const [stepUpError, setStepUpError] = useState<ApiError | null>(null);
  const verify = useCallback(() => {
    setStepUpError(null);
    ensureStepUp().catch((cause: unknown) => {
      const error = cause instanceof ApiError ? cause : new ApiError(0, {}, undefined);
      setStepUpError(error);
      toast(t(error.messageKey, error.args));
    });
  }, [ensureStepUp, toast, t]);
  useEffect(() => {
    if (searchOpen) searchInput.current?.focus();
  }, [searchOpen]);

  // Escape closes the drawer and the search; the page behind an open drawer does not scroll.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return;
      setNavOpen(false);
      setSearchOpen(false);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);
  useEffect(() => {
    document.documentElement.classList.toggle('fl-noscroll', navOpen && compact);
    return () => document.documentElement.classList.remove('fl-noscroll');
  }, [navOpen, compact]);

  useEffect(() => {
    onUnauthorized((reason) => router.replace(reason === 'offence' ? '/?signedOut=offence' : '/'));
    return () => onUnauthorized(null);
  }, [router]);

  useEffect(() => {
    // OD-130: the probe's 401 may be the one that says why (the client's handler has told it too).
    if (me.isError) {
      const offence = me.error instanceof ApiError && me.error.code === 'AUTH-401-OFFENCE';
      router.replace(offence ? '/?signedOut=offence' : '/');
    }
    if (me.data) adopt(me.data.locale);
  }, [me.isError, me.error, me.data, router, adopt]);

  const items = useMemo(() => menu.data?.sections.flatMap((s) => s.items) ?? [], [menu.data]);
  // No screen requested: the dashboard (home after sign-in and after a scope switch).
  const screen = params.get('s');
  // The menu is the wireframe's and only a convenience; the right to open a screen is its VIEW
  // permission (OD-121, SRS-G-13). A built screen the pack draws no menu entry for (My Submissions,
  // Users, Batch Jobs ...) stays open to whoever holds it, by link and by the search box.
  const outside = useMemo<MenuItem[]>(
    () =>
      Object.keys(SCREENS)
        .filter((code) => can(me.data, code, 'VIEW') && !items.some((i) => i.code === code))
        .map((code) => ({
          id: code,
          code,
          labelKey: `screen.${code}`,
          route: `/payroll?s=${code}`,
          requires: `${code}:VIEW`,
        })),
    [items, me.data],
  );
  const permitted = items.some((i) => i.code === screen) || outside.some((i) => i.code === screen);
  const scopeId = me.data ? `${me.data.role ?? ''}@${me.data.location?.code ?? 'eseva'}` : null;

  // A session that opens in a sensitive role is asked for the OTP before it proceeds (once per scope;
  // cancelling leaves the dashboard, and every sensitive screen asks again).
  useEffect(() => {
    if (!ready || !me.data?.stepUpRequired || !scopeId || askedAtOpen.current === scopeId) return;
    askedAtOpen.current = scopeId;
    verify();
  }, [ready, me.data?.stepUpRequired, scopeId, verify]);

  // A sensitive screen whose confirmation has expired asks before it opens.
  const screenNeedsStepUp = Boolean(me.data && permitted && needsStepUp(me.data, screen));
  // OD-130: a screen reached through the address bar without VIEW: told to the log, no offence (a
  // stale link gets here without a tool, and the page below fetches nothing).
  const deniedScreen = screen && menu.data && me.data && !permitted ? screen : null;
  useEffect(() => {
    if (deniedScreen) reportDeniedScreen(deniedScreen);
  }, [deniedScreen]);
  useEffect(() => {
    if (screenNeedsStepUp) verify();
  }, [screenNeedsStepUp, screen, verify]);
  const hits = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return [];
    return [...items, ...outside]
      .filter((i) => `${t(i.labelKey)} ${i.code}`.toLowerCase().includes(q))
      .slice(0, 10);
  }, [items, outside, query, t]);

  if (!me.data) return null;
  const session: Me = me.data;

  function go(code: string) {
    setNavOpen(false);
    setSearchOpen(false);
    setQuery('');
    router.push(`/payroll?s=${code}`);
  }

  /** Opens a sidebar entry on its own route (a screen drawn under several groups opens its view). */
  function openItem(item: MenuItem) {
    setNavOpen(false);
    setSearchOpen(false);
    setQuery('');
    router.push(item.route);
  }

  // The route of the entry that is open: the screen with the view the sidebar entry carries, so
  // only that entry of a screen drawn twice is marked current.
  const current = `/payroll?s=${screen ?? ''}${[...params.entries()]
    .filter(([k]) => k !== 's')
    .map(([k, v]) => `&${k}=${v}`)
    .join('')}`;

  async function signOut() {
    await endSession();
    router.replace('/');
  }

  function periodSwitched(next: Me) {
    toast(t('period.switched', [periodLabel(next.period, locale)]));
  }

  function switched(next: Me) {
    const role = next.role ? t(`role.${next.role}`) : (next.roleName ?? '');
    toast(
      next.location ? t('scope.switched', [role, next.location.label]) : t('scope.switchedEseva', [role]),
    );
    // Stay on the open screen when the new scope may view it: its data reloads for the new office
    // (the queries were reset; the screen remounts, see scopeKey). Deep-link parameters of the previous
    // office (an EIS) are dropped. Otherwise the dashboard.
    if (screen && next.permissions.includes(`${screen}:VIEW`)) router.replace(`/payroll?s=${screen}`);
    else router.push('/payroll');
  }

  if (session.passwordChangeRequired || changingPassword) {
    return (
      <div className="wf fl-centered">
        <ChangePassword
          forced={session.passwordChangeRequired}
          onDone={async () => {
            setChangingPassword(false);
            await refreshSession();
            toast(t('password.changed'));
          }}
          onCancel={session.passwordChangeRequired ? () => void signOut() : () => setChangingPassword(false)}
        />
      </div>
    );
  }

  const roleName = session.role ? t(`role.${session.role}`) : (session.roleName ?? '');
  // A new office or role remounts the open screen: no record, form or filter of the previous scope stays.
  // The open screen remounts for another office, role or payroll period.
  const scopeKey = `${session.role ?? session.roleName ?? ''}@${session.location?.code ?? 'eseva'}#${session.period}`;
  const nav = menu.data?.sections ?? [];
  const hidden = menu.data?.hiddenCount ?? 0;
  const setAll = (open: boolean) => setClosed(open ? {} : Object.fromEntries(nav.map((s) => [s.id, true])));

  const context = (
    <>
      <div className="ctx">
        <span aria-hidden="true">📅</span>
        <PeriodSwitcher session={session} enabled={ready} onSwitched={periodSwitched} onError={toast} />
      </div>
      <ScopeSwitcher enabled={ready} onSwitched={switched} onError={toast} />
      <LanguageToggle className="fl-lang-top" />
    </>
  );

  return (
    <div className={`wf${navOpen ? ' navopen' : ''}${searchOpen ? ' searching' : ''}`}>
      <div className="top">
        <button
          type="button"
          className="navtoggle"
          title={t('nav.menu')}
          aria-label={t('nav.menu')}
          aria-expanded={navOpen}
          onClick={() => setNavOpen(!navOpen)}
        >
          ☰
        </button>
        <div className="brand">
          <button
            type="button"
            className="home"
            title={t('dash.title')}
            onClick={() => router.push('/payroll')}
          >
            <span className="logo">{t('brand.fci')}</span> {t('top.payroll')}
          </button>
          {screen && <span className="wfbadge">{screen}</span>}
        </div>
        <div className={`search${searchOpen ? ' open' : ''}`}>
          <div className="in">
            <span aria-hidden="true">🔎</span>
            <input
              ref={searchInput}
              type="search"
              enterKeyHint="go"
              value={query}
              aria-label={t('top.search')}
              placeholder={t('top.search')}
              autoComplete="off"
              onChange={(e) => setQuery(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Escape') setQuery('');
                if (e.key !== 'Enter') return;
                const first = hits[0];
                if (first) go(first.code);
                else if (/^[A-Z0-9]{4,20}$/i.test(query.trim()) && items.some((i) => i.code === 'M-01'))
                  router.push(`/payroll?s=M-01&eis=${query.trim().toUpperCase()}`);
              }}
            />
          </div>
          <div className={`results${query.trim() ? ' on' : ''}`} role="listbox" aria-label={t('top.search')}>
            {hits.length > 0 ? (
              hits.map((item) => (
                <button
                  type="button"
                  role="option"
                  aria-selected={item.code === screen}
                  key={item.code}
                  className="res"
                  onClick={() => go(item.code)}
                >
                  <div>
                    <b>{t(item.labelKey)}</b>
                    <div className="m">{item.code}</div>
                  </div>
                </button>
              ))
            ) : (
              <div className="res">
                <span className="m">{t('top.noMatch')}</span>
              </div>
            )}
          </div>
        </div>
        <div className="spacer" />
        {!compact && context}
        {compact && (
          <button
            type="button"
            className="searchtoggle"
            aria-label={searchOpen ? t('top.searchClose') : t('top.searchOpen')}
            aria-expanded={searchOpen}
            onClick={() => setSearchOpen(!searchOpen)}
          >
            {searchOpen ? <X size={18} /> : <Search size={18} />}
          </button>
        )}
        <div className="acct">
          <button
            type="button"
            className="me"
            title={`${session.displayName} · ${roleName}`}
            aria-label={t('account.menu')}
            aria-expanded={account}
            onClick={() => setAccount(!account)}
          >
            {roleInitials(session.roleName ?? session.displayName)}
          </button>
          {account && (
            <div className="acct-menu" role="menu">
              <div className="who">
                <b>{session.displayName}</b>
                <span>{session.loginId}</span>
              </div>
              <button
                type="button"
                role="menuitem"
                onClick={() => {
                  setAccount(false);
                  setChangingPassword(true);
                }}
              >
                <KeyRound size={14} /> {t('account.changePassword')}
              </button>
              {installPrompt.available && (
                <button
                  type="button"
                  role="menuitem"
                  onClick={() => {
                    setAccount(false);
                    void installPrompt.install();
                  }}
                >
                  <Download size={14} /> {t('pwa.install')}
                </button>
              )}
              <button type="button" role="menuitem" onClick={() => void signOut()}>
                <LogOut size={14} /> {t('account.signOut')}
              </button>
            </div>
          )}
        </div>
      </div>
      <div className="navscrim" role="presentation" onClick={() => setNavOpen(false)} />
      <div className="shell">
        <aside className="side" aria-label={t('nav.label')}>
          {compact && (
            <div className="side-ctx" role="group" aria-label={t('nav.context')}>
              {context}
            </div>
          )}
          <div className="rolebar">
            <b>👤 {roleName}</b>
            <div className="loc">
              {session.location ? `🏢 ${session.location.label}` : t('scope.noOffice')}
            </div>
            {session.readOnlyConsolidated && <div className="m">{t('nav.readOnlyScope')}</div>}
            {!session.otpRequired && (
              <div className="m" role="note" title={t('nav.otpOffHint')}>
                ⚠ {t('nav.otpOff')}
              </div>
            )}
          </div>
          <div className="navtools">
            <button type="button" onClick={() => setAll(true)}>
              ⌄ {t('nav.expandAll')}
            </button>
            <button type="button" onClick={() => setAll(false)}>
              › {t('nav.collapseAll')}
            </button>
          </div>
          {nav.map((section) => {
            // The wireframe's first entry (D-01 Home) stands alone, without a group header (OD-121).
            const headerless = section.id === 'home';
            const open = headerless || !closed[section.id];
            const shown = open ? section.items : section.items.filter((i) => i.code === screen);
            return (
              <div key={section.id} className={headerless ? 'grp-home' : undefined}>
                {!headerless && (
                  <button
                    type="button"
                    className={`grp${open ? '' : ' closed'}`}
                    aria-expanded={open}
                    title={open ? t('nav.collapseGroup') : t('nav.expandGroup')}
                    onClick={() => setClosed({ ...closed, [section.id]: open })}
                  >
                    {t(section.labelKey)}
                    {!open && <span className="cnt">{section.items.length}</span>}
                    <span className="chev">⌄</span>
                  </button>
                )}
                {shown.map((item) => (
                  <NavItem
                    key={item.id}
                    item={item}
                    active={item.code === screen && item.route === current}
                    onOpen={openItem}
                  />
                ))}
              </div>
            );
          })}
          {menu.data && items.length === 0 && <div className="hidden-note">{t('nav.noScreens')}</div>}
          {hidden > 0 && <div className="hidden-note">{t('nav.hidden', [hidden])}</div>}
        </aside>
        <main className="main">
          {!online && (
            <div className="fl-offline" role="status">
              <WifiOff size={16} aria-hidden="true" /> {t('pwa.offline')}
            </div>
          )}
          {menu.data &&
            (!screen ? (
              <Dashboard me={session} items={items} onOpen={go} onVerify={verify} />
            ) : permitted && screenNeedsStepUp ? (
              <div className="denied">
                <div className="big">🔐</div>
                <h2>{t('stepup.title')}</h2>
                <p>{t('stepup.text', [screen])}</p>
                {stepUpError && (
                  <p className="fl-error" role="alert">
                    {t(stepUpError.messageKey, stepUpError.args)}
                  </p>
                )}
                <button type="button" className="btn pri" onClick={verify}>
                  {t('dash.otpVerify')}
                </button>
              </div>
            ) : permitted ? (
              <ScreenView
                key={scopeKey}
                code={screen}
                me={session}
                order={items.map((i) => i.code)}
                onNavigate={go}
                entries={items}
                route={current}
                onOpen={openItem}
              />
            ) : (
              <div className="denied">
                <div className="big">🔒</div>
                <h2>{t('denied.title')}</h2>
                <p>{t('denied.text', [screen ?? '—'])}</p>
              </div>
            ))}
        </main>
      </div>
      <IdleGuard
        idleTimeoutSeconds={session.session?.idleTimeoutSeconds ?? 900}
        onKeepAlive={keepAlive}
        onExpire={() => {
          void endSession().finally(() => router.replace('/?signedOut=idle'));
        }}
      />
    </div>
  );
}

function NavItem({
  item,
  active,
  onOpen,
}: {
  item: MenuItem;
  active: boolean;
  onOpen: (item: MenuItem) => void;
}) {
  const { t } = useI18n();
  const Icon = SCREENS[item.code]?.icon ?? FileText;
  // The glyph the wireframe draws before the entry (navicon.<id>, OD-121); the registry icon
  // only for an entry the pack does not draw (the fallback menu without navigation rows).
  const glyph = t(`navicon.${item.id}`);
  // The code the wireframe prints on the entry (navcode.<id>, OD-121/OD-125): the pack files some
  // functions under other codes (X-02 Approvals Inbox is built as W-01); the built code otherwise.
  const drawnCode = t(`navcode.${item.id}`);
  const tag = drawnCode === `navcode.${item.id}` ? item.code : drawnCode;
  return (
    <button
      type="button"
      className={`nav${active ? ' on' : ''}`}
      aria-current={active ? 'page' : undefined}
      onClick={() => onOpen(item)}
    >
      <span className="ic" aria-hidden="true">
        {glyph === `navicon.${item.id}` ? <Icon /> : glyph}
      </span>
      {t(item.labelKey)}
      <span className="tag">{tag}</span>
    </button>
  );
}
