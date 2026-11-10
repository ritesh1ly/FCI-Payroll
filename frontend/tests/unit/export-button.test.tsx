import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { onPermissions } from '@/shared/api';
import { I18nProvider } from '@/shared/i18n';
import { downloadName, ExportButton, exportHref } from '@/shared/ui';
import { t } from './support/harness';

/** SRS-G-13: every list export asks for the format, CSV or Excel, then downloads it. */

function show(href = '/nginx/fci-payroll/v1/off-cycle/export') {
  return render(
    <I18nProvider>
      <ExportButton href={href} what="Off-cycle cases" />
    </I18nProvider>,
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
  onPermissions(null);
});

describe('ExportButton', () => {
  it('OD_130: an export the session cannot pass is refused here and never sent', async () => {
    const fetched = vi.fn();
    vi.stubGlobal('fetch', fetched);
    onPermissions(() => ['M-01:VIEW']);
    show('/nginx/fci-payroll/v1/admin/audit/export');
    await userEvent.click(screen.getByRole('button', { name: t('action.export') }));
    await userEvent.click(screen.getByRole('link', { name: t('export.download') }));
    expect(await within(screen.getByRole('dialog')).findByRole('alert')).toBeTruthy();
    expect(
      fetched.mock.calls.map((c) => String(c[0])).filter((url) => url.includes('/audit/export')),
    ).toEqual([]);
  });

  it('SRS-G-13 (UI audit S13): a refused export is shown in the dialog and saves no file; it can be retried', async () => {
    const fetched = vi.fn(
      async () =>
        new Response(JSON.stringify({ errorCode: 'COM-413-EXPORT-SIZE', args: [50000] }), {
          status: 413,
          headers: { 'Content-Type': 'application/problem+json' },
        }),
    );
    vi.stubGlobal('fetch', fetched);
    show();
    await userEvent.click(screen.getByRole('button', { name: t('action.export') }));
    await userEvent.click(screen.getByRole('link', { name: t('export.download') }));
    const dialog = screen.getByRole('dialog');
    expect(await within(dialog).findByRole('alert')).toBeTruthy();
    // A body that is not a problem document: the status alone, still in the dialog.
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response('<html>', { status: 502 })),
    );
    await userEvent.click(within(dialog).getByRole('link', { name: t('export.download') }));
    expect(await within(dialog).findByRole('alert')).toBeTruthy();
    // A network failure as well.
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => Promise.reject(new TypeError('offline'))),
    );
    await userEvent.click(within(dialog).getByRole('link', { name: t('export.download') }));
    expect(await within(dialog).findByRole('alert')).toBeTruthy();
    await userEvent.click(within(dialog).getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('the downloaded file keeps the name the server gives it', () => {
    expect(downloadName('attachment; filename="audit-trail.csv"', 'export.csv')).toBe('audit-trail.csv');
    expect(downloadName("attachment; filename*=UTF-8''r%C3%A9sum%C3%A9.xlsx", 'x')).toBe('résumé.xlsx');
    expect(downloadName("attachment; filename*=UTF-8''bad%E0.csv", 'x')).toBe('bad%E0.csv');
    expect(downloadName(null, 'export.xlsx')).toBe('export.xlsx');
    expect(downloadName('inline', 'export.xlsx')).toBe('export.xlsx');
  });

  it('SRS-G-13: the format is chosen in a dialog; Excel is preselected', async () => {
    show();
    expect(screen.queryByRole('dialog')).toBeNull();
    const button = screen.getByRole('button', { name: t('action.export') });
    expect(button.getAttribute('aria-haspopup')).toBe('dialog');
    await userEvent.click(button);
    const dialog = screen.getByRole('dialog');
    expect(dialog.getAttribute('aria-modal')).toBe('true');
    expect(
      within(dialog).getByRole('heading', { name: t('export.title', ['Off-cycle cases']) }),
    ).toBeTruthy();
    expect(within(dialog).getByText(t('export.choose'))).toBeTruthy();
    const xlsx = dialog.querySelector('input[value="XLSX"]') as HTMLInputElement;
    expect(xlsx.checked).toBe(true);
    // Focus is in the dialog, on the chosen format.
    expect(document.activeElement).toBe(xlsx);
    const download = within(dialog).getByRole('link', { name: t('export.download') });
    expect(download.getAttribute('href')).toBe('/nginx/fci-payroll/v1/off-cycle/export?format=XLSX');
    expect(download.hasAttribute('download')).toBe(true);
    await userEvent.click(dialog.querySelector('input[value="CSV"]') as HTMLElement);
    expect(download.getAttribute('href')).toBe('/nginx/fci-payroll/v1/off-cycle/export?format=CSV');
  });

  it('downloading, cancelling and Escape all close the dialog', async () => {
    show();
    const open = () => userEvent.click(screen.getByRole('button', { name: t('action.export') }));
    await open();
    const fetched = vi.fn(
      async () =>
        new Response('a,b', {
          status: 200,
          headers: { 'Content-Disposition': 'attachment; filename="off-cycle.csv"' },
        }),
    );
    vi.stubGlobal('fetch', fetched);
    vi.stubGlobal(
      'URL',
      Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:x'), revokeObjectURL: vi.fn() }),
    );
    const download = screen.getByRole('link', { name: t('export.download') });
    await userEvent.click(download);
    // The file is fetched with the session cookie, handed to the browser, and the dialog closes.
    expect(fetched).toHaveBeenCalledWith(
      '/nginx/fci-payroll/v1/off-cycle/export?format=XLSX',
      expect.objectContaining({ credentials: 'same-origin', cache: 'no-store' }),
    );
    expect(await screen.findByRole('button', { name: t('action.export') })).toBeTruthy();
    expect(screen.queryByRole('dialog')).toBeNull();
    await open();
    await userEvent.click(screen.getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByRole('dialog')).toBeNull();
    await open();
    await userEvent.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('the format joins the filter already in the address', () => {
    expect(exportHref('/nginx/fci-payroll/v1/queries/export', 'CSV')).toBe(
      '/nginx/fci-payroll/v1/queries/export?format=CSV',
    );
    expect(exportHref('/nginx/fci-payroll/v1/queries/export?status=OPEN', 'XLSX')).toBe(
      '/nginx/fci-payroll/v1/queries/export?status=OPEN&format=XLSX',
    );
  });

  it('SRS-G-13: a downloaded file is saved under the server name and its object URL is released after a minute', async () => {
    const created: string[] = [];
    const revoked: string[] = [];
    vi.stubGlobal(
      'fetch',
      vi.fn(
        async () =>
          new Response('eis,name', {
            status: 200,
            headers: { 'Content-Disposition': 'attachment; filename="off-cycle.csv"' },
          }),
      ),
    );
    const make = vi.fn((blob: Blob) => {
      const url = `blob:fci/${blob.size}`;
      created.push(url);
      return url;
    });
    URL.createObjectURL = make as unknown as typeof URL.createObjectURL;
    URL.revokeObjectURL = ((url: string) => revoked.push(url)) as typeof URL.revokeObjectURL;
    const names: string[] = [];
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
      this: HTMLAnchorElement,
    ) {
      names.push(this.download);
    });
    const timers: { fn: () => void; ms: number | undefined }[] = [];
    const real = globalThis.setTimeout;
    const spy = vi.spyOn(globalThis, 'setTimeout').mockImplementation(((fn: () => void, ms?: number) => {
      if (ms === 60_000) {
        timers.push({ fn, ms });
        return 0 as unknown as ReturnType<typeof setTimeout>;
      }
      return real(fn, ms);
    }) as typeof setTimeout);
    try {
      show();
      await userEvent.click(screen.getByRole('button', { name: t('action.export') }));
      const dialog = screen.getByRole('dialog');
      await userEvent.click(dialog.querySelector('input[value="CSV"]') as HTMLElement);
      await userEvent.click(within(dialog).getByRole('link', { name: t('export.download') }));
      await waitFor(() => expect(names).toEqual(['off-cycle.csv']));
      // The dialog closes on success; the object URL stays until the minute is over.
      await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
      expect(revoked).toEqual([]);
      expect(timers).toHaveLength(1);
      timers[0]?.fn();
      expect(revoked).toEqual(created);
    } finally {
      click.mockRestore();
      spy.mockRestore();
    }
  });

  it('SRS-G-13: a file without a Content-Disposition is saved under the default name of its format', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response('PK', { status: 200 })),
    );
    URL.createObjectURL = (() => 'blob:fci/x') as typeof URL.createObjectURL;
    URL.revokeObjectURL = (() => undefined) as typeof URL.revokeObjectURL;
    const names: string[] = [];
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
      this: HTMLAnchorElement,
    ) {
      names.push(this.download);
    });
    try {
      show();
      await userEvent.click(screen.getByRole('button', { name: t('action.export') }));
      await userEvent.click(screen.getByRole('link', { name: t('export.download') }));
      await waitFor(() => expect(names).toEqual(['export.xlsx']));
    } finally {
      click.mockRestore();
    }
  });
});
