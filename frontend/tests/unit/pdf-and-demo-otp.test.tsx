import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { documentPdfPath, payslipPdfPath } from '@/modules/eseva/business/esevaRules';
import { LoginOtpStep } from '@/modules/iam/components/LoginOtpStep';
import { useGoverned } from '@/modules/workflow';
import { ApiError } from '@/shared/api';
import { I18nProvider } from '@/shared/i18n';
import { DemoOtpCode, EXPORT_FORMATS, ExportButton } from '@/shared/ui';
import { backend, captureDownloads, me, problem, sent, t, wrap } from './support/harness';

/**
 * OD-146 (the demo OTP code viewer in both OTP prompts) and OD-144 (PDF in every list export; the eSeva PDF paths).
 */

afterEach(() => vi.unstubAllGlobals());

function Runner() {
  const run = useGoverned();
  const [done, setDone] = useState('');
  return (
    <button
      type="button"
      onClick={() =>
        run(async (otp?: { challengeId: number; code: string }) => {
          if (!otp) throw new ApiError(428, { errorCode: 'AUTH-428-OTP', args: ['7'] }, undefined);
          return 'ok';
        }).then(setDone, () => setDone('failed'))
      }
    >
      act {done}
    </button>
  );
}

describe('OD-146 demo OTP code viewer', () => {
  it('OD_146_onADemoStackTheOtpDialogShowsTheUsersOwnCodeOnRequest', async () => {
    const calls = backend({
      'GET /auth/otp/demo-code': () => ({ action: 'APPROVAL_DECISION', code: '482913', validSeconds: 540 }),
    });
    wrap(<Runner />, me(['W-01:VIEW'], { demo: true }));
    await userEvent.click(screen.getByRole('button', { name: /act/ }));
    const dialog = await screen.findByRole('dialog');
    // Never fetched before the user asks; never filled into the code field.
    expect(sent(calls, 'GET', '/auth/otp/demo-code')).toHaveLength(0);
    await userEvent.click(within(dialog).getByRole('button', { name: t('otp.demo.show') }));
    expect(await within(dialog).findByText(t('otp.demo.code', ['482913']))).toBeTruthy();
    expect(sent(calls, 'GET', '/auth/otp/demo-code')).toHaveLength(1);
    expect((within(dialog).getByLabelText(t('otp.code')) as HTMLInputElement).value).toBe('');
  });

  it('OD_146_outsideADemoStackTheOtpDialogHasNoViewer', async () => {
    backend({});
    wrap(<Runner />, me(['W-01:VIEW'], { demo: false }));
    await userEvent.click(screen.getByRole('button', { name: /act/ }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).queryByRole('button', { name: t('otp.demo.show') })).toBeNull();
  });

  it('OD_146_aViewerSwitchedOffOrAnExpiredCodeSaysThereIsNone', async () => {
    backend({ 'GET /auth/otp/demo-code': () => problem(404, 'COM-404-NOT-FOUND') });
    render(
      <I18nProvider>
        <DemoOtpCode />
      </I18nProvider>,
    );
    await userEvent.click(screen.getByRole('button', { name: t('otp.demo.show') }));
    expect(await screen.findByText(t('otp.demo.none'))).toBeTruthy();
  });

  it('OD_146_theSignInStepAsksForThePendingSignInsCodeOnlyOnADemoStack', async () => {
    const calls = backend({
      'GET /auth/login/otp/demo-code': () => ({ action: 'AUTH_LOGIN', code: '100200', validSeconds: 300 }),
    });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const sent0 = {
      otpRequired: true as const,
      channel: 'SMS' as const,
      destination: '******1234',
      validSeconds: 600,
    };
    const view = render(
      <I18nProvider>
        <QueryClientProvider client={client}>
          <LoginOtpStep sent={sent0} onSignedIn={async () => undefined} onBack={() => undefined} demo />
        </QueryClientProvider>
      </I18nProvider>,
    );
    await userEvent.click(screen.getByRole('button', { name: t('otp.demo.show') }));
    expect(await screen.findByText(t('otp.demo.code', ['100200']))).toBeTruthy();
    expect(sent(calls, 'GET', '/auth/login/otp/demo-code')).toHaveLength(1);
    view.rerender(
      <I18nProvider>
        <QueryClientProvider client={client}>
          <LoginOtpStep sent={sent0} onSignedIn={async () => undefined} onBack={() => undefined} />
        </QueryClientProvider>
      </I18nProvider>,
    );
    expect(screen.queryByRole('button', { name: t('otp.demo.show') })).toBeNull();
  });
});

describe('OD-144 PDF', () => {
  it('SRS_G_13_everyListExportOffersPdfAndDownloadsIt', async () => {
    expect(EXPORT_FORMATS).toEqual(['CSV', 'XLSX', 'PDF']);
    const calls = backend({ 'GET /v1/off-cycle/export': () => ({}) });
    const files = captureDownloads();
    try {
      render(
        <I18nProvider>
          <ExportButton href="/nginx/fci-payroll/v1/off-cycle/export" what="Off-cycle" />
        </I18nProvider>,
      );
      await userEvent.click(screen.getByRole('button', { name: t('action.export') }));
      const dialog = screen.getByRole('dialog');
      expect(within(dialog).getByText(t('export.pdfHint'))).toBeTruthy();
      await userEvent.click(dialog.querySelector('input[value="PDF"]') as HTMLElement);
      await userEvent.click(within(dialog).getByRole('link', { name: t('export.download') }));
      await waitFor(() => expect(files.names).toEqual(['export.pdf']));
      expect(calls.some((c) => c.url.endsWith('/v1/off-cycle/export?format=PDF'))).toBe(true);
    } finally {
      files.restore();
    }
  });

  it('OD_144_eachMyDocumentsRowHasItsServerPdfOrNone', () => {
    const slip = { kind: 'PAY_SLIP', period: '202609', state: 'FINAL', id: null };
    const form16 = { kind: 'FORM_16', period: '2025-26', state: 'ISSUED', id: 7 };
    expect(documentPdfPath(slip, null)).toBe('/v1/payslips/mine/202609/pdf');
    expect(documentPdfPath(slip, 41)).toBe('/v1/payslips/previews/41/202609/pdf');
    expect(documentPdfPath(form16, null)).toBe('/v1/tax/form16-certificates/7/part-b');
    // A support preview never reads a certificate; a certificate without an id has none.
    expect(documentPdfPath(form16, 41)).toBeNull();
    expect(documentPdfPath({ ...form16, id: null }, null)).toBeNull();
    expect(documentPdfPath({ ...slip, state: 'NOT_YET_AVAILABLE' }, null)).toBeNull();
    expect(documentPdfPath({ ...form16, state: 'NOT_DUE' }, null)).toBeNull();
    expect(
      documentPdfPath({ kind: 'CPF_SLIP', period: '2025-26', state: 'NOT_AVAILABLE', id: null }, null),
    ).toBeNull();
    expect(payslipPdfPath('202609', null)).toBe('/v1/payslips/mine/202609/pdf');
    expect(payslipPdfPath('202609', 3)).toBe('/v1/payslips/previews/3/202609/pdf');
  });
});
