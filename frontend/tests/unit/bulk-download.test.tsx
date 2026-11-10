import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MyPayslips } from '@/modules/employee/components/MyPayslips';
import { bulkFileName, bulkWhy, payslipDownloadPath } from '@/modules/employee/business/payslipBulkRules';
import { ApiError, apiFile, fileName, onPermissions, onStepUpRequired, onUnauthorized } from '@/shared/api';
import { I18nProvider } from '@/shared/i18n';
import { bulkAction, BulkJobButton, POLL_MS } from '@/shared/ui';
import { backend, captureDownloads, me, noContent, paged, problem, sent, t, wrap } from './support/harness';

/**
 * OD-149 (owner 2026-11-09: bulk downloads are a ZIP of single-month PDFs, a single month one PDF) at the UI layer:
 * the E-01 month picker (none, one, several, more than the setting, the setting not made), the O-01 / C-10 bulk job
 * button (states, a double click sends once, polling, the manifest count, download only when ready, 409 / 429 with
 * Retry-After), and the file download itself (Content-Disposition name, PDF vs ZIP, 401 / step-up / network /
 * timeout).
 */

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
  onPermissions(null);
  onStepUpRequired(null);
  onUnauthorized(null);
});

describe('E-01 bulk rules', () => {
  it('OD_149_noneIsDisabled_oneIsAPdf_severalNeedTheSettingAndAtMostItsNumber', () => {
    expect(bulkWhy(0, 12)).toEqual({ key: 'e01p.bulk.why.none', args: [] });
    expect(bulkWhy(1, null)).toBeNull();
    expect(bulkWhy(2, null)).toEqual({ key: 'e01p.bulk.why.notConfigured', args: [] });
    expect(bulkWhy(2, undefined)).toEqual({ key: 'e01p.bulk.why.notConfigured', args: [] });
    expect(bulkWhy(2, 0)).toEqual({ key: 'e01p.bulk.why.notConfigured', args: [] });
    expect(bulkWhy(12, 12)).toBeNull();
    expect(bulkWhy(13, 12)).toEqual({ key: 'e01p.bulk.why.tooMany', args: [12] });
  });

  it('OD_149_oneMonthIsItsPdf_severalOneZipOldestFirstWithoutDuplicates', () => {
    expect(payslipDownloadPath(['202609'])).toBe('/v1/payslips/mine/202609/pdf');
    expect(payslipDownloadPath(['202609', '202607', '202609'])).toBe(
      '/v1/payslips/mine/zip?months=202607,202609',
    );
    expect(bulkFileName(['202609'])).toBe('payslip-202609.pdf');
    expect(bulkFileName(['202609', '202607'])).toBe('payslips-202607-202609.zip');
  });
});

const MONTHS = [
  { yymm: '202609', gross: 100, deductions: 10, net: 90 },
  { yymm: '202608', gross: 100, deductions: 10, net: 90 },
  { yymm: '202607', gross: 100, deductions: 10, net: 90 },
];

function member(max: number | null) {
  return backend({
    'GET /v1/payslips/mine?': (url) => paged(MONTHS, MONTHS.length, url),
    'GET /v1/payslips/mine/bulk-settings': () => ({ maxPeriods: max }),
    'GET /v1/payslips/mine/zip': () => ({}),
    'GET /v1/payslips/mine/2026': () => ({}),
  });
}

const download = () => screen.getByRole('button', { name: t('e01p.bulk.download') });

describe('E-01 My payslips bulk download', () => {
  it('OD_149_nothingChosenIsDisabledWithTheReason_oneMonthDownloadsItsPdf', async () => {
    const calls = member(2);
    const files = captureDownloads();
    try {
      wrap(<MyPayslips />, me(['E-01:VIEW', 'E-01:SELF']));
      const boxes = await screen.findAllByRole('checkbox');
      expect(boxes).toHaveLength(3);
      expect(download()).toBeDisabled();
      expect(download()).toHaveAttribute('data-why', t('e01p.bulk.why.none'));
      await userEvent.click(boxes[0] as HTMLElement);
      expect(screen.getByText(t('e01p.bulk.count', [1]))).toBeTruthy();
      await userEvent.click(download());
      await waitFor(() => expect(files.names).toEqual(['payslip-202609.pdf']));
      expect(sent(calls, 'GET', '/v1/payslips/mine/202609/pdf')).toHaveLength(1);
    } finally {
      files.restore();
    }
  });

  it('OD_149_severalMonthsDownloadOneZip_moreThanTheSettingIsRefusedHereWithItsNumber', async () => {
    const calls = member(2);
    const files = captureDownloads();
    try {
      wrap(<MyPayslips />, me(['E-01:VIEW', 'E-01:SELF']));
      const boxes = await screen.findAllByRole('checkbox');
      await userEvent.click(boxes[0] as HTMLElement);
      await userEvent.click(boxes[2] as HTMLElement);
      await userEvent.click(download());
      await waitFor(() => expect(files.names).toEqual(['payslips-202607-202609.zip']));
      expect(sent(calls, 'GET', '/v1/payslips/mine/zip?months=202607,202609')).toHaveLength(1);
      await userEvent.click(boxes[1] as HTMLElement);
      await waitFor(() => expect(download()).toBeDisabled());
      expect(download()).toHaveAttribute('data-why', t('e01p.bulk.why.tooMany', [2]));
      // Un-ticking brings it back within the setting.
      await userEvent.click(boxes[1] as HTMLElement);
      expect(download()).toBeEnabled();
    } finally {
      files.restore();
    }
  });

  it('OD_149_whileTheSettingIsNotMadeOnlyOneMonthAtATime', async () => {
    member(null);
    wrap(<MyPayslips />, me(['E-01:VIEW', 'E-01:SELF']));
    const boxes = await screen.findAllByRole('checkbox');
    await userEvent.click(boxes[0] as HTMLElement);
    await waitFor(() => expect(download()).toBeEnabled());
    await userEvent.click(boxes[1] as HTMLElement);
    expect(download()).toBeDisabled();
    expect(download()).toHaveAttribute('data-why', t('e01p.bulk.why.notConfigured'));
  });

  it('OD_149_aStaffViewerSeesNoPickerAndAsksNoSetting', async () => {
    const calls = member(2);
    wrap(<MyPayslips />, me(['E-01:VIEW']));
    await screen.findByText('202609', { exact: false }).catch(() => null);
    expect(screen.queryByRole('checkbox')).toBeNull();
    expect(screen.queryByRole('button', { name: t('e01p.bulk.download') })).toBeNull();
    expect(calls.some((c) => c.url.includes('bulk-settings'))).toBe(false);
  });

  it('OD_149_aRefusedDownloadIsShownAndSavesNoFile', async () => {
    backend({
      'GET /v1/payslips/mine?': (url) => paged(MONTHS, MONTHS.length, url),
      'GET /v1/payslips/mine/bulk-settings': () => ({ maxPeriods: 2 }),
      'GET /v1/payslips/mine/zip': () => problem(404, 'COM-404-NOT-FOUND'),
    });
    const files = captureDownloads();
    try {
      wrap(<MyPayslips />, me(['E-01:VIEW', 'E-01:SELF']));
      const boxes = await screen.findAllByRole('checkbox');
      await userEvent.click(boxes[0] as HTMLElement);
      await userEvent.click(boxes[1] as HTMLElement);
      await userEvent.click(download());
      expect(await screen.findByRole('alert')).toBeTruthy();
      expect(files.names).toEqual([]);
    } finally {
      files.restore();
    }
  });
});

function button(why: string | null = null) {
  return render(
    <I18nProvider>
      <BulkJobButton
        label="Bulk PDF"
        statusPath="/v1/payslip-bulk-files?yymm=202609&level=STAFF"
        requestPath="/v1/payslip-bulk-files"
        requestBody={{ yymm: '202609', level: 'STAFF' }}
        filePath={(id) => `/v1/payslip-bulk-files/${id}/file`}
        fileName="payslips-202609.zip"
        why={why}
      />
    </I18nProvider>,
  );
}

describe('The bulk job button (O-01, C-10)', () => {
  it('OD_149_whatAPressDoesFollowsTheJobsState', () => {
    expect(bulkAction(null)).toBe('request');
    for (const s of ['AWAITING_APPROVAL', 'PENDING', 'PICKED', 'RUNNING', 'PAUSED'])
      expect(bulkAction(s)).toBe('wait');
    expect(bulkAction('COMPLETED')).toBe('download');
    for (const s of ['FAILED', 'STOPPED', 'ABANDONED', 'REJECTED']) expect(bulkAction(s)).toBe('request');
  });

  it('OD_149_withoutAJobAPressQueuesOneOnce_thenItWaits', async () => {
    const calls = backend({
      'GET /v1/payslip-bulk-files': () => noContent(),
      'POST /v1/payslip-bulk-files': () => ({ jobRequestId: 91, status: 'PENDING', written: 0, leftOut: 0 }),
    });
    button();
    const b = screen.getByRole('button', { name: 'Bulk PDF' });
    await waitFor(() => expect(sent(calls, 'GET', '/v1/payslip-bulk-files?')).toHaveLength(1));
    await userEvent.dblClick(b);
    expect(await screen.findByText(t('bulk.state.PENDING', [0, 0]))).toBeTruthy();
    expect(sent(calls, 'POST', '/v1/payslip-bulk-files')).toEqual([{ yymm: '202609', level: 'STAFF' }]);
    expect(b).toBeDisabled();
    expect(b).toHaveAttribute('title', t('bulk.why.running'));
    expect(b).toHaveAttribute('aria-describedby');
  });

  it('OD_149_aRunningJobIsAskedForAgainUntilItIsReadyThenTheZipDownloads', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    let status = 'RUNNING';
    const calls = backend({
      'GET /v1/payslip-bulk-files/91/file': () => ({}),
      'GET /v1/payslip-bulk-files': () => ({ jobRequestId: 91, status, written: 120, leftOut: 2 }),
    });
    const files = captureDownloads();
    try {
      button();
      expect(await screen.findByText(t('bulk.state.RUNNING', [120, 2]), { exact: false })).toBeTruthy();
      status = 'COMPLETED';
      await act(async () => {
        await vi.advanceTimersByTimeAsync(POLL_MS + 10);
      });
      expect(await screen.findByText(t('bulk.state.COMPLETED', [120, 2]), { exact: false })).toBeTruthy();
      expect(screen.getByText(t('bulk.leftOut', [2]), { exact: false })).toBeTruthy();
      const b = screen.getByRole('button', { name: 'Bulk PDF' });
      expect(b).toBeEnabled();
      await userEvent.click(b);
      await waitFor(() => expect(files.names).toEqual(['payslips-202609.zip']));
      expect(sent(calls, 'GET', '/v1/payslip-bulk-files/91/file')).toHaveLength(1);
    } finally {
      files.restore();
    }
  });

  it('OD_149_aFailedJobIsAskedForAgainWithANewRequest', async () => {
    const calls = backend({
      'GET /v1/payslip-bulk-files': () => ({ jobRequestId: 90, status: 'FAILED', written: 0, leftOut: 0 }),
      'POST /v1/payslip-bulk-files': () => ({ jobRequestId: 91, status: 'PENDING', written: 0, leftOut: 0 }),
    });
    button();
    expect(await screen.findByText(t('bulk.state.FAILED', [0, 0]))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: 'Bulk PDF' }));
    await waitFor(() => expect(sent(calls, 'POST', '/v1/payslip-bulk-files')).toHaveLength(1));
  });

  it('OD_149_aButtonThatCannotActIsDisabledWithItsReasonAndAsksNothing', async () => {
    const calls = backend({});
    button('Not locked');
    const b = screen.getByRole('button', { name: 'Bulk PDF' });
    expect(b).toBeDisabled();
    expect(b).toHaveAttribute('data-why', 'Not locked');
    await new Promise((r) => setTimeout(r, 20));
    expect(calls.filter((c) => c.url.includes('payslip-bulk-files'))).toEqual([]);
  });

  it('OD_149_aRefusedRequestIsShown', async () => {
    backend({
      'GET /v1/payslip-bulk-files': () => noContent(),
      'POST /v1/payslip-bulk-files': () => problem(409, 'JOB-409-ALREADY-QUEUED'),
    });
    button();
    await userEvent.click(screen.getByRole('button', { name: 'Bulk PDF' }));
    expect(await screen.findByRole('alert')).toBeTruthy();
  });

  it('OD_144_aBusyServerSaysWhenToTryAgainAndTheButtonWaitsThatLong', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) =>
        String(url).endsWith('/file')
          ? new Response(JSON.stringify({ errorCode: 'COM-503-EXPORT-BUSY' }), {
              status: 503,
              headers: { 'Retry-After': '5', 'Content-Type': 'application/problem+json' },
            })
          : new Response(JSON.stringify({ jobRequestId: 91, status: 'COMPLETED', written: 1, leftOut: 0 }), {
              status: 200,
              headers: { 'Content-Type': 'application/json' },
            }),
      ),
    );
    button();
    const b = await screen.findByRole('button', { name: 'Bulk PDF' });
    await screen.findByText(t('bulk.state.COMPLETED', [1, 0]), { exact: false });
    await userEvent.click(b);
    expect(await screen.findByText(t('download.retryIn', [5]))).toBeTruthy();
    expect(b).toBeDisabled();
  });
});

describe('apiFile', () => {
  it('OD_144_theServersFileNameAndTypeAreKept', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(
        async () =>
          new Response('PK', {
            status: 200,
            headers: {
              'Content-Type': 'application/zip',
              'Content-Disposition': 'attachment; filename="payslips-202601-202603.zip"',
            },
          }),
      ),
    );
    const f = await apiFile('/v1/payslips/mine/zip?months=202601,202603');
    expect(f.name).toBe('payslips-202601-202603.zip');
    expect(f.type).toBe('application/zip');
    expect(fileName(null)).toBeNull();
    expect(fileName("attachment; filename*=UTF-8''bad%E0.pdf")).toBe('bad%E0.pdf');
  });

  it('OD_144_aRefusalCarriesItsCodeAndRetryAfter_aBadRetryAfterIsIgnored', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(
        async () =>
          new Response(JSON.stringify({ errorCode: 'COM-429-TOO-MANY' }), {
            status: 429,
            headers: { 'Retry-After': 'soon' },
          }),
      ),
    );
    const e = (await apiFile('/v1/x/pdf').catch((x: unknown) => x)) as ApiError;
    expect(e.status).toBe(429);
    expect(e.code).toBe('COM-429-TOO-MANY');
    expect(e.retryAfterSeconds).toBeNull();
  });

  it('OD_144_anExpiredSessionGoesToSignIn', async () => {
    const signedOut = vi.fn();
    onUnauthorized(signedOut);
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response('{}', { status: 401 })),
    );
    await expect(apiFile('/v1/x/pdf')).rejects.toBeInstanceOf(ApiError);
    expect(signedOut).toHaveBeenCalledWith('expired');
  });

  it('SRS_G_48_aStepUpIsAskedAndTheDownloadMadeOnceMore', async () => {
    onStepUpRequired(async () => true);
    const fetched = vi
      .fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ errorCode: 'AUTH-403-STEP-UP' }), { status: 403 }))
      .mockResolvedValueOnce(
        new Response('%PDF-', { status: 200, headers: { 'Content-Type': 'application/pdf' } }),
      );
    vi.stubGlobal('fetch', fetched);
    const f = await apiFile('/v1/x/pdf');
    expect(f.type).toBe('application/pdf');
    expect(fetched).toHaveBeenCalledTimes(2);
  });

  it('OD_144_aNetworkFailureAndATimeoutAreTold', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => Promise.reject(new TypeError('offline'))),
    );
    const net = (await apiFile('/v1/x/pdf').catch((x: unknown) => x)) as ApiError;
    expect(net.messageKey).toBe('error.network');
    vi.stubGlobal(
      'fetch',
      vi.fn(
        (_url: string, init?: RequestInit) =>
          new Promise((_ok, fail) =>
            init?.signal?.addEventListener('abort', () => fail(new DOMException('a', 'AbortError'))),
          ),
      ),
    );
    const slow = (await apiFile('/v1/x/pdf', 10).catch((x: unknown) => x)) as ApiError;
    expect(slow.messageKey).toBe('download.timeout');
    expect(slow.status).toBe(0);
  });

  it('OD_130_aCallTheSessionCannotPassIsNeverSent', async () => {
    const fetched = vi.fn();
    vi.stubGlobal('fetch', fetched);
    onPermissions(() => ['E-01:VIEW']);
    await expect(apiFile('/v1/payslips/mine/zip?months=202601,202602')).rejects.toBeInstanceOf(ApiError);
    expect(fetched).not.toHaveBeenCalled();
  });
});
