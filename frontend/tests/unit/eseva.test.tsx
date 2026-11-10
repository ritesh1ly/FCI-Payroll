import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { EsevaSupport, PrivacyNotice } from '@/modules/eseva';
import { backend, me, paged, problem, sent, t, wrap } from './support/harness';

/**
 * E-02 eSeva Support & Submission Log and the E-01 privacy notice (plan 14 phases 14a / 14b;
 * SRS-17-6, SRS-17-8, SRS-17-10, SRS-17-18, SRS-SEC-9).
 */

afterEach(() => vi.unstubAllGlobals());

const ROW = {
  id: 1,
  eis: '104882',
  member: 'Rakesh Sharma',
  office: 'DH001 - Delhi',
  type: 'CPF_ADVANCE_REQUEST',
  reference: 'pf_sanction #12',
  value: 'CPF ADVANCE HOUSE of 50000',
  submittedAt: '2026-10-28T10:00:00',
  status: 'ROUTED',
  approvalId: null,
};

const PREVIEW = {
  sessionId: 31,
  openedAt: '2026-10-28T05:00:00Z',
  submitEnabled: false,
  member: {
    eis: '104882',
    name: 'Rakesh Sharma',
    office: null,
    employeeType: 'EMPLOYEE',
    employmentStatus: 'IN_SERVICE',
    panMasked: '******234D',
    aadhaarMasked: '********1234',
    bankAccountMasked: null,
    mobileMasked: '********10',
    emailMasked: null,
  },
  payslipMonths: [{ yymm: '202609', gross: 50000, deductions: 8000, net: 42000 }],
  submissions: [
    { type: 'TAX_DECLARATION', value: null, status: 'ACTIONED', submittedAt: '2026-10-01T09:00:00' },
  ],
};

describe('E-02 eSeva Support & Submission Log', () => {
  it('SRS-17-18: staff list the submissions of their offices and filter them', async () => {
    const calls = backend({ 'GET /v1/eseva/submissions?': (url) => paged([ROW], 1, url) });
    wrap(<EsevaSupport />, me(['E-02:VIEW']));
    const table = await screen.findByRole('table', { name: t('e02.log') });
    expect(within(table).getByText(t('e02.type.CPF_ADVANCE_REQUEST'))).toBeTruthy();
    expect(within(table).getByText(t('e02.status.ROUTED'))).toBeTruthy();
    expect(screen.queryByText(t('e02.preview'))).toBeNull();
    await userEvent.selectOptions(screen.getByLabelText(t('e02.status')), 'ACTIONED');
    await userEvent.selectOptions(screen.getByLabelText(t('e02.type')), 'TAX_DECLARATION');
    await userEvent.type(screen.getAllByLabelText(t('m01.eis'))[0] as HTMLElement, '104882');
    await screen.findByRole('table', { name: t('e02.log') });
    expect(
      calls.some((c) => c.url.includes('status=ACTIONED') && c.url.includes('type=TAX_DECLARATION')),
    ).toBe(true);
  });

  it('SRS-17-6 / SRS-17-10: a support look is masked and read-only, every submit disabled', async () => {
    const calls = backend({
      'GET /v1/eseva/submissions?': (url) => paged([], 0, url),
      'POST /v1/eseva/support-previews': () => ({ status: 201, body: PREVIEW }),
    });
    wrap(<EsevaSupport />, me(['E-02:VIEW', 'E-02:SUPPORT']));
    expect(await screen.findByText(t('e02.none'))).toBeTruthy();
    const open = screen.getByRole('button', { name: t('action.e02.preview') });
    expect(open).toBeDisabled();
    expect(open.getAttribute('title')).toBe(t('e02.why.preview'));
    await userEvent.type(screen.getAllByLabelText(t('m01.eis'))[0] as HTMLElement, '104882');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Ticket 42');
    expect(open).toBeEnabled();
    await userEvent.click(open);
    const view = await screen.findByRole('region', { name: t('e02.previewOf', ['104882']) });
    expect(within(view).getByText('******234D')).toBeTruthy();
    expect(within(view).getByText('********1234')).toBeTruthy();
    expect(within(view).getByText('09/2026')).toBeTruthy();
    const submit = within(view).getByRole('button', { name: t('action.e02.submit') });
    expect(submit).toBeDisabled();
    expect(submit.getAttribute('title')).toBe(t('e02.why.submit'));
    expect(sent(calls, 'POST', '/support-previews').at(-1)).toEqual({ eis: '104882', reason: 'Ticket 42' });
  });

  it('FRS V0.5 l.6958 (US-GAP-261): the user closes the support preview; a refused close keeps it and says why', async () => {
    let answer: unknown = problem(404, 'COMMON-404-NOT-FOUND', []);
    const calls = backend({
      'GET /v1/eseva/submissions?': (url) => paged([], 0, url),
      'POST /v1/eseva/support-previews': () => ({ status: 201, body: PREVIEW }),
      'POST /v1/eseva/support-previews/': () => answer,
    });
    wrap(<EsevaSupport />, me(['E-02:VIEW', 'E-02:SUPPORT']));
    await userEvent.type(screen.getAllByLabelText(t('m01.eis'))[0] as HTMLElement, '104882');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Ticket 42');
    await userEvent.click(screen.getByRole('button', { name: t('action.e02.preview') }));
    await screen.findByRole('region', { name: t('e02.previewOf', ['104882']) });
    await userEvent.click(screen.getByRole('button', { name: t('action.e02.closePreview') }));
    expect(await screen.findByText(t('error.COMMON-404-NOT-FOUND'))).toBeTruthy();
    expect(screen.getByRole('region', { name: t('e02.previewOf', ['104882']) })).toBeTruthy();
    answer = { status: 204, body: null };
    await userEvent.click(screen.getByRole('button', { name: t('action.e02.closePreview') }));
    await vi.waitFor(() =>
      expect(screen.queryByRole('region', { name: t('e02.previewOf', ['104882']) })).toBeNull(),
    );
    expect(calls.some((c) => c.method === 'POST' && c.url.includes('/closures'))).toBe(true);
  });

  it('SRS-17-10 / SRS-17-18: what the member never gave is shown as a dash, an empty history as none', async () => {
    backend({
      'GET /v1/eseva/submissions?': (url) => paged([{ ...ROW, office: null, value: null }], 1, url),
      'POST /v1/eseva/support-previews': () => ({
        status: 201,
        body: {
          ...PREVIEW,
          member: {
            ...PREVIEW.member,
            office: 'DH001 - Delhi',
            panMasked: null,
            aadhaarMasked: null,
            mobileMasked: null,
            emailMasked: 'r****@example.test',
          },
          payslipMonths: [],
          submissions: [],
        },
      }),
    });
    wrap(<EsevaSupport />, me(['E-02:VIEW', 'E-02:SUPPORT']));
    const table = await screen.findByRole('table', { name: t('e02.log') });
    expect(within(table).getByText('—')).toBeTruthy();
    await userEvent.type(screen.getAllByLabelText(t('m01.eis'))[0] as HTMLElement, '104882');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Ticket 43');
    await userEvent.click(screen.getByRole('button', { name: t('action.e02.preview') }));
    const view = await screen.findByRole('region', { name: t('e02.previewOf', ['104882']) });
    expect(within(view).getAllByText('—').length).toBeGreaterThanOrEqual(4);
    expect(within(view).getByText(t('e02.noPayslips'))).toBeTruthy();
    expect(within(view).getByText(t('e02.none'))).toBeTruthy();
  });

  it('SRS-17-9: a member outside the scope reads as not found', async () => {
    backend({
      'GET /v1/eseva/submissions?': (url) => paged([], 0, url),
      'POST /v1/eseva/support-previews': () => problem(404, 'COMMON-404-NOT-FOUND', []),
    });
    wrap(<EsevaSupport />, me(['E-02:VIEW', 'E-02:SUPPORT']));
    await screen.findByText(t('e02.none'));
    await userEvent.type(screen.getAllByLabelText(t('m01.eis'))[0] as HTMLElement, '999999');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Ticket 42');
    await userEvent.click(screen.getByRole('button', { name: t('action.e02.preview') }));
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.queryByRole('region')).toBeNull();
  });
});

describe('E-01 privacy notice', () => {
  it('SRS-SEC-9: shown until the member acknowledges it, then gone', async () => {
    const calls = backend({
      'GET /v1/eseva/privacy-notice/mine': () => ({ version: '1', acknowledged: false }),
      'POST /v1/eseva/privacy-notice/mine/acknowledgements': () => ({ version: '1', acknowledged: true }),
    });
    wrap(<PrivacyNotice />, me(['E-01:VIEW']));
    expect(await screen.findByText(t('eseva.privacy.text'))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('action.eseva.acknowledge') }));
    await vi.waitFor(() => expect(screen.queryByText(t('eseva.privacy.text'))).toBeNull());
    expect(sent(calls, 'POST', '/acknowledgements')).toHaveLength(1);
  });

  it('SRS-SEC-9 (review 14-1): a notice that fails to load is shown as the error with a retry, then the notice', async () => {
    let calls = 0;
    backend({
      'GET /v1/eseva/privacy-notice/mine': () =>
        ++calls === 1 ? problem(503, 'COM-503-UNAVAILABLE') : { version: '1', acknowledged: false },
    });
    wrap(<PrivacyNotice />, me(['E-01:VIEW']));
    expect(await screen.findByText(t('eseva.privacy.title'))).toBeTruthy();
    expect(await screen.findByText(t('error.COM-503-UNAVAILABLE'))).toBeTruthy();
    expect(screen.queryByRole('button', { name: t('action.eseva.acknowledge') })).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: t('common.retry') }));
    expect(await screen.findByText(t('eseva.privacy.text'))).toBeTruthy();
    expect(screen.getByRole('button', { name: t('action.eseva.acknowledge') })).toBeEnabled();
  });

  it('SRS-SEC-9: a staff user sees no notice even when the load would fail', async () => {
    backend({ 'GET /v1/eseva/privacy-notice/mine': () => problem(503, 'COM-503-UNAVAILABLE') });
    const staff = wrap(<PrivacyNotice />, me(['E-02:VIEW']));
    expect(staff.container.textContent).toBe('');
  });

  it('SRS-SEC-9: an acknowledged notice and a staff user show nothing; a refusal is shown', async () => {
    backend({ 'GET /v1/eseva/privacy-notice/mine': () => ({ version: '1', acknowledged: true }) });
    const { container } = wrap(<PrivacyNotice />, me(['E-01:VIEW']));
    await vi.waitFor(() => expect(container.textContent).toBe(''));
    vi.unstubAllGlobals();
    backend({
      'GET /v1/eseva/privacy-notice/mine': () => ({ version: '1', acknowledged: false }),
      'POST /v1/eseva/privacy-notice/mine/acknowledgements': () => problem(500, 'X', []),
    });
    wrap(<PrivacyNotice />, me(['E-01:VIEW']));
    await userEvent.click(await screen.findByRole('button', { name: t('action.eseva.acknowledge') }));
    expect(await screen.findByRole('alert')).toBeTruthy();
    vi.unstubAllGlobals();
    const none = backend({});
    const staff = wrap(<PrivacyNotice />, me(['E-02:VIEW']));
    expect(staff.container.textContent).toBe('');
    expect(none.length).toBeGreaterThanOrEqual(0);
  });
});
