import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { messages } from '@/generated/messages';
import { format, I18nProvider, isLocale, LanguageToggle, translate, useI18n } from '@/shared/i18n';

function Probe() {
  const { t, locale } = useI18n();
  return (
    <p data-testid="probe">
      {locale}:{t('login.signIn')}:{t('login.footer', [2026])}:{t('no.such.key')}
    </p>
  );
}

describe('i18n', () => {
  it('has identical key sets in English and Hindi, with no empty Hindi label', () => {
    expect(Object.keys(messages.hi).sort()).toEqual(Object.keys(messages.en).sort());
    for (const value of Object.values(messages.hi)) expect(value.trim()).not.toBe('');
  });

  it('formats MessageFormat placeholders and leaves unknown ones', () => {
    expect(format('{0} of {1}', ['a', 2])).toBe('a of 2');
    expect(format('{0} {3}', ['x'])).toBe('x {3}');
    expect(translate('hi', 'login.signIn')).toBe(messages.hi['login.signIn']);
    expect(translate('en', 'missing.key')).toBe('missing.key');
    expect(isLocale('hi')).toBe(true);
    expect(isLocale('fr')).toBe(false);
  });

  it('switches EN | हिं and stores the choice through the BFF', async () => {
    const fetchMock = vi.fn(async (...args: [RequestInfo | URL, RequestInit?]) =>
      String(args[0]).endsWith('/nginx/fci-payroll/i18n')
        ? new Response(JSON.stringify({ locale: 'en' }), { status: 200 })
        : new Response(null, { status: 204 }),
    );
    vi.stubGlobal('fetch', fetchMock);
    document.cookie = 'XSRF-TOKEN=t1; Path=/';
    render(
      <I18nProvider>
        <LanguageToggle />
        <Probe />
      </I18nProvider>,
    );
    expect(screen.getByTestId('probe').textContent).toBe(
      `en:Sign in:© 2026 Food Corporation of India:no.such.key`,
    );
    await userEvent.click(screen.getByRole('button', { name: 'हिं' }));
    expect(screen.getByTestId('probe').textContent).toContain(`hi:${messages.hi['login.signIn']}`);
    expect(document.documentElement.lang).toBe('hi');
    const put = fetchMock.mock.calls.find(([url]) => String(url).endsWith('/nginx/fci-payroll/auth/locale'));
    expect(put?.[1]?.method).toBe('PUT');
    expect(put?.[1]?.body).toBe(JSON.stringify({ locale: 'hi' }));
    // And back to English.
    await userEvent.click(screen.getByRole('button', { name: 'EN' }));
    expect(screen.getByTestId('probe').textContent).toContain('en:Sign in');
    expect(document.documentElement.lang).toBe('en');
    vi.unstubAllGlobals();
  });
});
