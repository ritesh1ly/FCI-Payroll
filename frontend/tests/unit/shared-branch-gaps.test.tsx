import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { I18nProvider } from '@/shared/i18n';
import { ReferenceOptions } from '@/shared/reference';
import { guardScreen } from '@/shared/server/guards';
import { Dropdown, ExportButton, ReasonDialog, ServerPagination } from '@/shared/ui';
import { backend, me, referenceLists, t, wrap } from './support/harness';

/**
 * Remaining edges of the shared UI: an export (SRS-G-13) named by its format when the server names
 * no file, Escape alone closing its dialog and a second click ignored while it downloads; the
 * dropdown's arrows from the list itself; a page answer without a body shown as a problem; a reason
 * dialog submitted before its reason; a screen code of the wrong shape sent to the landing page;
 * the options of a reference list narrowed to the allowed codes; the server-side backend address.
 */

const cookieJar = new Map<string, string>();
vi.mock('next/headers', () => ({
  cookies: async () => ({
    getAll: () => [...cookieJar].map(([name, value]) => ({ name, value })),
    has: (name: string) => cookieJar.has(name),
  }),
  headers: async () => ({ get: () => null }),
}));

afterEach(() => {
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
});

describe('ExportButton', () => {
  function show() {
    return render(
      <I18nProvider>
        <ExportButton href="/nginx/fci-payroll/v1/off-cycle/export" what="Off-cycle cases" />
      </I18nProvider>,
    );
  }

  it('SRS-G-13: a CSV the server does not name is saved as export.csv; other keys keep the dialog open', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response('a,b', { status: 200 })),
    );
    vi.stubGlobal(
      'URL',
      Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:x'), revokeObjectURL: vi.fn() }),
    );
    const names: string[] = [];
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
      this: HTMLAnchorElement,
    ) {
      names.push(this.download);
    });
    show();
    await userEvent.click(screen.getByRole('button', { name: t('action.export') }));
    const dialog = screen.getByRole('dialog');
    // A key other than Escape does not close the dialog.
    fireEvent.keyDown(dialog, { key: 'Enter' });
    expect(screen.getByRole('dialog')).toBeTruthy();
    await userEvent.click(dialog.querySelector('input[value="CSV"]') as HTMLElement);
    await userEvent.click(within(dialog).getByRole('link', { name: t('export.download') }));
    await waitFor(() => expect(names).toEqual(['export.csv']));
    click.mockRestore();
  });

  it('SRS-G-13: a click on Download while the file is still coming is ignored', async () => {
    let finish: (r: Response) => void = () => undefined;
    const fetched = vi.fn((url: string) =>
      url.includes('/off-cycle/export')
        ? new Promise<Response>((resolve) => {
            finish = resolve;
          })
        : Promise.resolve(new Response('{}', { status: 200 })),
    );
    vi.stubGlobal('fetch', fetched);
    show();
    await userEvent.click(screen.getByRole('button', { name: t('action.export') }));
    const dialog = screen.getByRole('dialog');
    fireEvent.click(within(dialog).getByRole('link', { name: t('export.download') }));
    const busy = await within(dialog).findByRole('link', { name: t('common.loading') });
    expect(busy.getAttribute('aria-disabled')).toBe('true');
    fireEvent.click(busy);
    const exports = () => fetched.mock.calls.filter(([url]) => url.includes('/off-cycle/export'));
    expect(exports()).toHaveLength(1);
    finish(
      new Response(JSON.stringify({ errorCode: 'COM-413-EXPORT-SIZE', args: [50000] }), { status: 413 }),
    );
    expect(await within(dialog).findByRole('alert')).toBeTruthy();
  });
});

describe('Dropdown', () => {
  const options = [
    { value: 'a', label: 'Alpha' },
    { value: 'b', label: 'Beta' },
    { value: 'c', label: 'Gamma' },
  ];

  it('the arrows from the list itself go to the first option down and the last option up', async () => {
    render(<Dropdown label="Pick" value="z" onChange={vi.fn()} options={options} />);
    await userEvent.click(screen.getByRole('button', { name: 'Pick' }));
    const list = screen.getByRole('listbox');
    list.focus();
    fireEvent.keyDown(list, { key: 'ArrowDown' });
    expect(document.activeElement).toBe(screen.getByRole('option', { name: /Alpha/ }));
    list.focus();
    fireEvent.keyDown(list, { key: 'ArrowUp' });
    expect(document.activeElement).toBe(screen.getByRole('option', { name: /Gamma/ }));
    // Any other key moves nothing.
    fireEvent.keyDown(list, { key: 'a' });
    expect(document.activeElement).toBe(screen.getByRole('option', { name: /Gamma/ }));
  });
});

describe('ServerPagination', () => {
  it('a page answered without a body is shown as a problem, never as an empty list', async () => {
    backend({});
    wrap(
      <ServerPagination queryKey={['gaps', 'none']} fetchPage={async () => null as never}>
        {() => <p>rows</p>}
      </ServerPagination>,
    );
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.queryByText('rows')).toBeNull();
  });
});

describe('ReasonDialog', () => {
  it('BR-AUDIT-003: submitting before a reason is given confirms nothing', async () => {
    const onConfirm = vi.fn();
    render(
      <I18nProvider>
        <ReasonDialog title="Return" onConfirm={onConfirm} onCancel={vi.fn()} />
      </I18nProvider>,
    );
    const form = screen.getByRole('dialog');
    fireEvent.submit(form);
    expect(onConfirm).not.toHaveBeenCalled();
    await userEvent.type(within(form).getByRole('textbox'), '  Amount differs ');
    fireEvent.submit(form);
    expect(onConfirm).toHaveBeenCalledWith('Amount differs');
  });
});

describe('guards', () => {
  it('SRS-G-2: a screen code of the wrong shape goes to the landing page, never echoed', () => {
    expect(guardScreen(me(['M-04:VIEW']), '../admin')).toBe('/payroll');
    expect(guardScreen(me(['M-04:VIEW']), 'm-04')).toBe('/payroll');
    expect(guardScreen(me(['M-04:VIEW']), 'M-04')).toBeNull();
    expect(guardScreen(me([]), null)).toBeNull();
  });
});

describe('ReferenceOptions', () => {
  it('SRS-G-1: the options of a list can be narrowed to the codes a field allows', async () => {
    backend({
      'GET /v1/reference/lists': () =>
        referenceLists({
          RATE_UNIT: [
            ['PERCENT', 'Percent'],
            ['INR', 'Rupees'],
          ],
        }),
    });
    wrap(
      <select aria-label="unit">
        <ReferenceOptions list="RATE_UNIT" only={['INR']} />
      </select>,
    );
    expect(await screen.findByRole('option', { name: 'Rupees' })).toBeTruthy();
    expect(screen.queryByRole('option', { name: 'Percent' })).toBeNull();
  });
});

describe('server backend address', () => {
  async function load() {
    vi.resetModules();
    return import('@/shared/server/backend');
  }

  it('OD-158: in production without a configured BACKEND_URL no backend is called (no built-in address); in development the dev backend', async () => {
    const fetched = vi.fn<(url: string) => Promise<Response>>(
      async () => new Response(JSON.stringify({ demo: true }), { status: 200 }),
    );
    vi.stubGlobal('fetch', fetched);
    vi.stubEnv('BACKEND_URL', undefined);
    vi.stubEnv('FCI_WEB_ENV', '/no/such/web.env');
    vi.stubEnv('FCI_CONFIG_DIR', '/no/such/dir');
    vi.stubEnv('NODE_ENV', 'production');
    // The server would not have started (instrumentation.ts); a call still guesses nothing.
    expect(await (await load()).serverLoginOptions()).toBeNull();
    expect(fetched).not.toHaveBeenCalled();
    vi.stubEnv('BACKEND_URL', 'http://127.0.0.1:8643/fci-payroll');
    await (await load()).serverLoginOptions();
    expect(fetched.mock.calls[0]?.[0]).toBe('http://127.0.0.1:8643/fci-payroll/auth/login-options');
    vi.stubEnv('BACKEND_URL', undefined);
    vi.stubEnv('NODE_ENV', 'development');
    vi.stubEnv('FCI_DEV_BACKEND_URL', 'http://localhost:8743');
    await (await load()).serverLoginOptions();
    expect(fetched.mock.calls[1]?.[0]).toBe('http://localhost:8743/api/auth/login-options');
  });
});
