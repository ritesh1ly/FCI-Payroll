import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MyPayslips } from '@/modules/employee';
import { backend, me, paged, problem, t, wrap } from './support/harness';

/**
 * E-01 My payslips (plan 16 phase 16e-3; SRS-1-10, SRS-17-1, SRS-17-7): the locked months, one
 * month's payslip with earnings and deductions side by side and the masked identifiers; a refusal
 * is shown; without E-01 the card is not shown.
 */

afterEach(() => vi.unstubAllGlobals());

const SLIP = {
  yymm: '202609',
  eis: '104882',
  name: 'Rakesh Sharma',
  designation: 'Assistant Grade I',
  office: 'Delhi HQ',
  payPattern: 'IDA',
  panMasked: 'ABCPE****K',
  bankAccountMasked: '********4321',
  pran: '110012345678',
  earnings: [
    { payCode: 101, name: 'Basic Pay', group: 'PAY', amount: 30000 },
    { payCode: 111, name: 'Dearness Allowance', group: 'ALLOWANCES', amount: 15000 },
  ],
  deductions: [{ payCode: 904, name: 'CPF employee contribution', group: 'DEDUCTIONS', amount: 5400 }],
  gross: 45000,
  totalDeductions: 5400,
  net: 39600,
};

describe('My payslips', () => {
  it('SRS-17-1 / SRS-1-10: the locked months and one payslip in the staff layout', async () => {
    backend({
      'GET /v1/payslips/mine?': (url) =>
        paged([{ yymm: '202609', gross: 45000, deductions: 5400, net: 39600 }], 1, url),
      'GET /v1/payslips/mine/202609': () => SLIP,
    });
    wrap(<MyPayslips />, me(['E-01:VIEW']));
    const month = await screen.findByRole('button', { name: /Sept?.*2026/ });
    await userEvent.click(month);
    const panel = await screen.findByRole('region', { name: /2026/ });
    expect(await within(panel).findByText('ABCPE****K')).toBeTruthy();
    expect(within(panel).getByText('********4321')).toBeTruthy();
    expect(within(panel).getByText('110012345678')).toBeTruthy();
    expect(within(panel).getByText(t('e01p.pran'))).toBeTruthy();
    const earnings = within(panel).getByRole('table', { name: t('e01p.earnings') });
    expect(within(earnings).getByText('Dearness Allowance')).toBeTruthy();
    const deductions = within(panel).getByRole('table', { name: t('e01p.deductions') });
    expect(within(deductions).getByText('CPF employee contribution')).toBeTruthy();
    await userEvent.click(within(panel).getByRole('button', { name: t('common.close') }));
    expect(screen.queryByRole('region', { name: /2026/ })).toBeNull();
  });

  it('SRS-17-1: an empty side shows a dash; a refusal is shown; without E-01 nothing shows', async () => {
    backend({
      'GET /v1/payslips/mine?': (url) => paged([{ yymm: '202608', gross: 1, deductions: 0, net: 1 }], 1, url),
      'GET /v1/payslips/mine/202608': () => ({
        ...SLIP,
        yymm: '202608',
        deductions: [],
        designation: null,
        pran: null,
        office: null,
        panMasked: null,
        bankAccountMasked: null,
      }),
    });
    const view = wrap(<MyPayslips />, me(['E-01:VIEW']));
    await userEvent.click(await screen.findByRole('button', { name: /Aug.*2026/ }));
    const deductions = await screen.findByRole('table', { name: t('e01p.deductions') });
    expect(within(deductions).getAllByText('—').length).toBeGreaterThan(0);
    expect(screen.queryByText(t('e01p.pran'))).toBeNull();
    // No office, PAN or bank account on record: each shows a dash.
    expect(screen.getAllByText('—').length).toBeGreaterThanOrEqual(4);
    view.unmount();
    backend({
      'GET /v1/payslips/mine?': (url) => paged([{ yymm: '202607', gross: 1, deductions: 0, net: 1 }], 1, url),
      'GET /v1/payslips/mine/202607': () => problem(404, 'COM-404-NOT-FOUND'),
    });
    const second = wrap(<MyPayslips />, me(['E-01:VIEW']));
    await userEvent.click(await screen.findByRole('button', { name: /Jul.*2026/ }));
    expect(await screen.findByRole('alert')).toBeTruthy();
    second.unmount();
    backend({});
    const none = wrap(<MyPayslips />, me(['M-01:VIEW']));
    expect(none.container.textContent).toBe('');
  });
});
