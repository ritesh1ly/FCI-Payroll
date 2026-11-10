import { fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { FinalSettlement } from '@/modules/settlement';
import { settlementApi } from '@/modules/settlement/api/settlementApi';
import { backend, me, paged, sent, t, wrap } from './support/harness';

/**
 * Edge cases of O-05 Full & Final Settlement (SRS-10-19, SRS-10-22, SRS-10-24, SRS-10-25): an
 * invoice status not yet recorded, Release disabled for a Maker with the reason, a sanction letter
 * chosen and removed again, every recovery taken from the gratuity, a recovery returned to the
 * payroll dues without a reason.
 */

afterEach(() => vi.unstubAllGlobals());

const label = (key: string) => new RegExp(`^${t(key).replace(/[.*+?^${}()|[\]\\/]/g, '\\$&')}`);

const SUMMARY = {
  separations: 0,
  settled: 0,
  settledAmount: 0,
  byType: [],
  awaitingClearance: 0,
  hqApproval: 0,
  netRecovery: 0,
};

const ROW = {
  caseId: 7,
  caseReference: 'FNF-2026-00007',
  eis: '104882',
  name: 'Rakesh Sharma',
  office: 'DH001 - Delhi Division',
  separationType: 'RETIREMENT',
  separationDate: '2026-09-30',
  stage: 'CLEARANCES_RECEIVED',
  awaiting: [],
  status: 'INITIATED',
  netPayable: 74500,
  payrollType: 'STAFF',
  payrollDues: 74500,
  totalRecoveries: null,
  recoveredFromGratuity: null,
  awaitingChecker: false,
  hqApprovalPending: false,
};

const detail = (over: object = {}) => ({
  summary: ROW,
  settlementId: 3,
  clearances: [{ type: 'ESTABLISHMENT_NO_DUES', status: 'RECEIVED', remark: 'Nil' }],
  pendingSalary: null,
  leaveEncashment: null,
  arrears: null,
  exGratia: null,
  gratuity: 200000,
  totalRecoveries: 1200,
  recoveredFromGratuity: 1200,
  netRecovery: false,
  recoveries: [],
  exGratiaEntries: [],
  cpfStatus: 'NOT_APPLIED',
  cpfAmount: null,
  benefitsHeld: false,
  invoiceStatus: null,
  cpfSanctionNumber: null,
  ...over,
});

function routes(view: unknown = detail()) {
  return backend({
    'GET /v1/settlement-cases/awaiting': () => [],
    'GET /v1/settlement-cases/summary': () => SUMMARY,
    'GET /v1/settlement-cases/invoices': (url) => paged([], 0, url),
    'GET /v1/settlement-cases?': (url) => paged([ROW], 1, url),
    'GET /v1/settlement-cases/7': () => view,
  });
}

async function openCase() {
  await userEvent.click(await screen.findByRole('button', { name: t('common.view') }));
  await screen.findByRole('table', { name: t('o05.recoveries') });
}

describe('O-05 Full & Final Settlement edges', () => {
  it('SRS-10-19: an invoice not yet recorded is shown as not raised; Release is for Finance only', async () => {
    routes();
    wrap(<FinalSettlement />, me(['O-05:VIEW', 'O-05:COMPUTE']));
    await openCase();
    expect(screen.getByText(t('o05.invoice.NOT_RAISED'))).toBeTruthy();
    const release = screen.getByRole('button', { name: t('action.o05.release') });
    expect(release).toBeDisabled();
    expect(release).toHaveAttribute('title', t('reason.financeOnly'));
  });

  it('SRS-10-19: Finance may release only an approved settlement and is told why', async () => {
    routes();
    wrap(<FinalSettlement />, me(['O-05:VIEW', 'O-05:RELEASE']));
    await openCase();
    const release = screen.getByRole('button', { name: t('action.o05.release') });
    expect(release).toBeDisabled();
    expect(release).toHaveAttribute('title', t('o05.why.release'));
  });

  it('SRS-10-24: when every recovery came from the gratuity none is shown against the net payable', async () => {
    routes();
    wrap(<FinalSettlement />, me(['O-05:VIEW']));
    await openCase();
    const components = screen.getByRole('table', { name: t('o05.components') });
    const dues = components.querySelector('tr[data-component="DUES"]') as HTMLElement;
    expect(within(dues).getAllByRole('cell').at(4)?.textContent).toBe('—');
  });

  it('SRS-10-25: a sanction letter chosen and removed again leaves the ex-gratia unsendable', async () => {
    routes();
    wrap(<FinalSettlement />, me(['O-05:VIEW', 'O-05:COMPUTE']));
    await openCase();
    await userEvent.click(screen.getByRole('button', { name: t('action.o05.exGratia') }));
    const form = screen.getByLabelText(t('action.o05.exGratia'), { selector: 'div' });
    const send = within(form).getByRole('button', { name: t('common.submitForApproval') });
    await userEvent.type(within(form).getByLabelText(label('o05.letterNo')), 'EG/12');
    fireEvent.change(within(form).getByLabelText(label('o05.letterDate')), {
      target: { value: '2026-10-01' },
    });
    await userEvent.type(within(form).getByLabelText(label('o05.amount')), '50000');
    await userEvent.type(within(form).getByLabelText(label('o05.issuingAuthority')), 'GM (P)');
    const letter = within(form).getAllByLabelText(label('o05.letter')).at(-1) as HTMLInputElement;
    await userEvent.upload(letter, new File(['%PDF'], 'letter.pdf', { type: 'application/pdf' }));
    expect(send).toBeEnabled();
    fireEvent.change(letter, { target: { files: [] } });
    expect(send).toBeDisabled();
  });

  it('SRS-10-22: a recovery returned to the payroll dues without a reason sends none', async () => {
    const calls = backend({ 'PUT /v1/settlement-cases/7/recoveries/41/source': () => detail() });
    await settlementApi.source(7, 41, { source: 'PAYROLL_DUES', reason: '' });
    const body = sent(calls, 'PUT', '/recoveries/41/source')[0] as FormData;
    expect(body.get('source')).toBe('PAYROLL_DUES');
    expect(body.has('reason')).toBe(false);
    expect(body.has('order')).toBe(false);
  });
});
