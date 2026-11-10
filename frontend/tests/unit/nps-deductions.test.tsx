import { fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { NpsDeductions } from '@/modules/nps';
import { backend, me, paged, t, wrap } from './support/harness';

/**
 * I-14 NPS Deduction (from HRMS) (plan 18 phase 18c; SRS-9-1, SRS-9-1a, SRS-9-2, SRS-9-6): view
 * only; the month's records with their HRMS / CALC / SYS sources, a mismatch marked as corrected in
 * HRMS, the counts; export and the report only with I-14 EXPORT and a valid month or range.
 */

afterEach(() => vi.unstubAllGlobals());

const ROWS = [
  {
    id: 2,
    eis: '301002',
    employee: 'Meena Iyer',
    office: 'Chennai',
    npsFlag: true,
    pran: '110087654321',
    employeeAmount: null,
    employerAmount: 1800,
    deducted: null,
    effectiveFrom: null,
    status: 'FLAG_WITHOUT_AMOUNT',
  },
  {
    id: 1,
    eis: '104882',
    employee: 'Rakesh Sharma',
    office: null,
    npsFlag: true,
    pran: '110012345678',
    employeeAmount: 1500,
    employerAmount: 2100,
    deducted: 1500,
    effectiveFrom: '2026-10-01',
    status: 'APPLIED',
  },
  {
    // An amount HRMS sent for a member it does not flag for NPS, without a PRAN.
    id: 3,
    eis: '104990',
    employee: 'Arun Das',
    office: 'Kolkata',
    npsFlag: false,
    pran: null,
    employeeAmount: 900,
    employerAmount: null,
    deducted: null,
    effectiveFrom: null,
    status: 'AMOUNT_WITHOUT_FLAG',
  },
];

describe('I-14 NPS deductions', () => {
  it('SRS-9-1a / SRS-9-2: the records, a mismatch to correct in HRMS, the counts and the exports', async () => {
    const calls = backend({
      'GET /v1/nps-deductions/counts': () => [
        { status: 'FLAG_WITHOUT_AMOUNT', count: 1 },
        { status: 'APPLIED', count: 1 },
      ],
      'GET /v1/nps-deductions?': (url) => paged(ROWS, 2, url),
    });
    wrap(<NpsDeductions />, me(['I-14:VIEW', 'I-14:EXPORT']));
    const table = await screen.findByRole('table', { name: t('screen.I-14') });
    expect(within(table).getByText('110012345678')).toBeTruthy();
    expect(within(table).getByText(t('i14.status.FLAG_WITHOUT_AMOUNT'))).toBeTruthy();
    const unflagged = within(table)
      .getByText(/104990/)
      .closest('tr') as HTMLElement;
    expect(within(unflagged).getByText(t('common.no'))).toBeTruthy();
    expect(within(unflagged).getAllByText('—').length).toBeGreaterThan(0);
    expect(within(table).getAllByText(t('i14.fixInHrms'))).toHaveLength(2);
    expect(within(table).getByText('₹2,100.00')).toBeTruthy();
    const counts = await screen.findByRole('list', { name: t('i14.counts') });
    expect(within(counts).getByText(`${t('i14.status.APPLIED')}: 1`)).toBeTruthy();
    expect(screen.getByRole('button', { name: t('action.export') })).toBeTruthy();
    expect(screen.getByRole('button', { name: t('i14.reportExport') })).toBeTruthy();
    expect(calls.some((c) => c.method !== 'GET')).toBe(false);

    await userEvent.selectOptions(screen.getByLabelText(t('i14.status')), 'APPLIED');
    await userEvent.type(screen.getByLabelText(t('m01.eis')), '104882');
    expect(calls.some((c) => c.url.includes('status=APPLIED') && c.url.includes('eis=104882'))).toBe(true);
  });

  it('SRS-9-6: a month or a range that is not one disables the export; without EXPORT none is offered', async () => {
    backend({
      'GET /v1/nps-deductions/counts': () => [],
      'GET /v1/nps-deductions?': (url) => paged([], 0, url),
    });
    const view = wrap(<NpsDeductions />, me(['I-14:VIEW', 'I-14:EXPORT']));
    expect(await screen.findByText(t('i14.none'))).toBeTruthy();
    // A month picker: a partial month is no month (UI audit 2026-11-02, month controls).
    const month = screen.getByLabelText(t('i14.month'));
    expect(month).toHaveAttribute('type', 'month');
    fireEvent.change(month, { target: { value: '' } });
    expect(screen.getByText(t('i14.badMonth'))).toBeTruthy();
    expect(screen.getByRole('button', { name: t('action.export') }).hasAttribute('disabled')).toBe(true);
    fireEvent.change(screen.getByLabelText(t('i14.from')), { target: { value: '2026-04' } });
    fireEvent.change(screen.getByLabelText(t('i14.to')), { target: { value: '2020-01' } });
    expect(screen.getByRole('button', { name: t('i14.reportExport') }).hasAttribute('disabled')).toBe(true);
    view.unmount();

    backend({
      'GET /v1/nps-deductions/counts': () => [],
      'GET /v1/nps-deductions?': (url) => paged(ROWS, 2, url),
    });
    wrap(<NpsDeductions />, me(['I-14:VIEW']));
    expect(await screen.findByRole('table', { name: t('screen.I-14') })).toBeTruthy();
    expect(screen.queryByRole('button', { name: t('action.export') })).toBeNull();
    expect(screen.queryByText(t('i14.report'))).toBeNull();
  });
});
