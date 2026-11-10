import { fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { LabourMaster } from '@/modules/labour/components/LabourMaster';
import { backend, me, paged, sent, t, wrap } from './support/harness';

/**
 * M-02 edges (plan 20 phase 20e; SRS-2-49, SRS-2-1): a member listed without a designation, depot
 * or retirement date shows the placeholders; Light Duty at Ancillary pay sends no protected Basic and
 * the order date chosen; clearing the designation sends none.
 */

afterEach(() => vi.unstubAllGlobals());

const label = (key: string) => new RegExp(`^${t(key).replace(/[.*+?^${}()|[\]\\/]/g, '\\$&')}`);

const OPTIONS = {
  depots: [{ id: 7, code: 'DP-DL01', name: 'Depot Okhla', family: null }],
  designations: [{ id: 5, code: 'ANC', name: 'Ancillary Labour', family: 'DEPT_LABOUR' }],
};

const BARE = {
  employeeId: 12,
  labourCode: 'DPT-000002',
  name: 'Shyam Lal',
  payrollType: 'DEPT_LABOUR',
  designation: null,
  depot: null,
  status: 'ACTIVE',
  joiningDate: '2012-01-01',
  retirementDate: null,
  panMasked: null,
  bankAccountMasked: null,
};

describe('M-02 edges', () => {
  it('SRS-2-1: a member without designation, depot or retirement date shows the placeholders', async () => {
    backend({
      'GET /v1/labour/options': () => OPTIONS,
      'GET /v1/labour/members?': (url) => paged([BARE], 1, url),
    });
    wrap(<LabourMaster />, me(['M-02:VIEW']));
    const table = await screen.findByRole('table', { name: t('screen.M-02') });
    const row = within(table).getByText('DPT-000002').closest('tr') as HTMLElement;
    // Designation, depot and the masked PAN / bank cell.
    expect(within(row).getAllByText('—')).toHaveLength(3);
    expect(within(row).getByText(t('m02.retirementNotSet'))).toBeTruthy();
  });

  it('SRS-2-49: Light Duty at Ancillary pay sends no protected Basic and the order date chosen', async () => {
    const calls = backend({
      'GET /v1/labour/options': () => OPTIONS,
      'GET /v1/labour/members?': (url) => paged([{ ...BARE, designation: 'HL - Handling Labour' }], 1, url),
      'POST /v1/labour/members/12/light-duty-orders': () => ({
        status: 202,
        body: { id: 81, status: 'PENDING_APPROVAL' },
      }),
    });
    wrap(<LabourMaster />, me(['M-02:VIEW', 'M-02:EDIT']));
    const table = await screen.findByRole('table', { name: t('screen.M-02') });
    await userEvent.click(within(table).getByRole('button', { name: t('action.m02.lightDuty') }));
    const designation = screen.getByLabelText(label('m02.toDesignation'));
    await userEvent.selectOptions(designation, '5');
    await userEvent.selectOptions(designation, '');
    await userEvent.selectOptions(designation, '5');
    await userEvent.type(screen.getByLabelText(label('m02.orderReference')), 'MB/8');
    fireEvent.change(screen.getByLabelText(label('m02.orderDate')), { target: { value: '2026-10-20' } });
    await userEvent.type(screen.getByLabelText(label('m02.issuingAuthority')), 'Medical Board');
    await userEvent.type(screen.getByLabelText(label('m09.changeReason')), 'Medical finding');
    await userEvent.click(screen.getByRole('button', { name: t('action.m02.send') }));
    expect(await screen.findByText(t('m02.lifecycleSent'))).toBeTruthy();
    expect(sent(calls, 'POST', '/light-duty-orders').at(-1)).toMatchObject({
      toDesignationId: 5,
      payOption: 'ANCILLARY_PAY',
      protectedBasic: null,
      orderDate: '2026-10-20',
    });
  });
});
