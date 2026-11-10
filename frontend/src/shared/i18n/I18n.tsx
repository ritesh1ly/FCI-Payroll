'use client';

import {
  createContext,
  type ReactNode,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import { type Locale, type MessageKey, messages } from '@/generated/messages';
import { api } from '@/shared/api';

/**
 * Labels only, never data values (names, codes, amounts stay as stored). The bundles are generated
 * at build time from the backend's messages_{en,hi}.properties. Locale resolution follows the backend:
 * stored preference > FCI_LOCALE cookie > Accept-Language > en.
 */
export type Translate = (key: MessageKey | string, args?: readonly unknown[]) => string;

interface I18nValue {
  locale: Locale;
  t: Translate;
  setLocale: (locale: Locale) => Promise<void>;
  /** Applies a locale resolved elsewhere (from /auth/me) without storing it again. */
  adopt: (locale: string | null | undefined) => void;
}

/** {0}, {1} … placeholders, as in java.text.MessageFormat (no nested formats). */
export function format(template: string, args: readonly unknown[] = []): string {
  return template.replace(/\{(\d+)\}/g, (whole, index: string) => {
    const value = args[Number(index)];
    return value === undefined || value === null ? whole : String(value);
  });
}

export function translate(locale: Locale, key: string, args?: readonly unknown[]): string {
  const bundle = messages[locale] as Record<string, string>;
  const fallback = messages.en as Record<string, string>;
  const template = bundle[key] ?? fallback[key];
  return template === undefined ? key : format(template, args);
}

export function isLocale(value: unknown): value is Locale {
  return value === 'en' || value === 'hi';
}

const I18nContext = createContext<I18nValue | null>(null);

export function I18nProvider({ children, initial = 'en' }: { children: ReactNode; initial?: Locale }) {
  const [locale, setLocaleState] = useState<Locale>(initial);
  // An explicit EN | हिं choice in this page wins over a resolution that answers later (/i18n, /auth/me).
  const chosen = useRef(false);

  useEffect(() => {
    document.documentElement.lang = locale;
  }, [locale]);

  useEffect(() => {
    let cancelled = false;
    api<{ locale: string }>('/i18n', { silent: true })
      .then((resolved) => {
        if (!cancelled && !chosen.current && isLocale(resolved.locale)) setLocaleState(resolved.locale);
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, []);

  const t = useCallback<Translate>((key, args) => translate(locale, key, args), [locale]);

  const setLocale = useCallback(async (next: Locale) => {
    chosen.current = true;
    setLocaleState(next);
    await api('/auth/locale', { method: 'PUT', body: { locale: next }, silent: true }).catch(() => undefined);
  }, []);

  const adopt = useCallback((value: string | null | undefined) => {
    if (isLocale(value) && !chosen.current) setLocaleState(value);
  }, []);

  const value = useMemo(() => ({ locale, t, setLocale, adopt }), [locale, t, setLocale, adopt]);
  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

export function useI18n(): I18nValue {
  const value = useContext(I18nContext);
  if (!value) throw new Error('useI18n outside I18nProvider');
  return value;
}

/** EN | हिं, next to "Need help?" on the login page and in the workspace top bar. */
export function LanguageToggle({ className = '' }: { className?: string }) {
  const { locale, setLocale, t } = useI18n();
  return (
    <span className={`fl-lang ${className}`} role="group" aria-label={t('lang.toggle')}>
      <button type="button" aria-pressed={locale === 'en'} onClick={() => void setLocale('en')} lang="en">
        {t('lang.en')}
      </button>
      <span aria-hidden="true">|</span>
      <button type="button" aria-pressed={locale === 'hi'} onClick={() => void setLocale('hi')} lang="hi">
        {t('lang.hi')}
      </button>
    </span>
  );
}
