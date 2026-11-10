import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ControlFlags } from '@/modules/employee/components/ControlFlags';
import { controlProposalComplete } from '@/modules/employee/business/flagRules';
import { todayIso } from '@/modules/general';
import { backend, me, problem, t, wrap } from './support/harness';

/**
 * M-01 Control flags (SRS-1-7, SRS-1-11; FRS §7.8.1 Scenario 4; US-GAP-4): Hold and Stop-Payment
 * with their value; Establishment/HR sets or clears one from a date with a reason and the order,
 * sent to the Payroll Checker; a flag waiting for the Checker cannot be changed and says why; a
 * later date is not offered (CLR-17); a viewer has no change action.
 */

afterEach(() => vi.unstubAllGlobals());

const TYPES = [{ code: 'OTHER', name: 'Other document', referenceRequired: false }];

const FLAGS = {
  eis: '100041',
  joiningDate: '2010-06-01',
  flags: [
    { code: 'HOLD', value: 'N', pending: false },
    { code: 'STOP_PAYMENT', value: 'Y', pending: true },
  ],
};

async function attachOrder(form: HTMLElement) {
  await userEvent.selectOptions(within(form).getByLabelText(t('ord.type')), 'OTHER');
  await userEvent.type(within(form).getByLabelText(t('ord.date')), '2026-01-15');
  await userEvent.type(within(form).getByLabelText(t('ord.authority')), 'Establishment, Delhi');
  await userEvent.upload(
    within(form).getByLabelText(t('ord.file')),
    new File(['%PDF-1.7'], 'order.pdf', { type: 'application/pdf' }),
  );
}

const changeHold = () => `${t('m01.control.change')} ${t('m01.control.HOLD')}`;

describe('M-01 control flags', () => {
  it('SRS-1-77: a separated record is read-only: no Hold or Stop-Payment change, with the reason', async () => {
    backend({
      'GET /v1/document-types': () => TYPES,
      'GET /v1/employees/100041/control-flags': () => FLAGS,
      'GET /v1/employees/100041': () => ({ eis: '100041', employmentStatus: 'SEPARATED' }),
    });
    wrap(<ControlFlags eis="100041" />, me(['M-01:VIEW', 'M-01:FLAG']));
    const hold = await screen.findByRole('button', { name: changeHold() });
    await vi.waitFor(() => expect(hold).toBeDisabled());
    expect(hold).toHaveAttribute('title', t('m01.why.separated'));
  });
  it('SRS-1-7: establishment sets a Hold with the order; it is sent to the checker', async () => {
    const calls = backend({
      'GET /v1/document-types': () => TYPES,
      'GET /v1/employees/100041/control-flags': () => FLAGS,
      'POST /v1/employees/100041/control-flags': () => ({
        status: 202,
        body: { approvalId: 81, status: 'PENDING_APPROVAL' },
      }),
    });
    wrap(<ControlFlags eis="100041" />, me(['M-01:VIEW', 'M-01:FLAG']));
    expect(await screen.findByText(t('m01.control.pending'))).toBeTruthy();
    const stop = screen.getByRole('button', {
      name: `${t('m01.control.change')} ${t('m01.control.STOP_PAYMENT')}`,
    });
    expect(stop).toBeDisabled();
    expect(stop).toHaveAttribute('title', t('m01.control.pending'));
    expect(stop).toHaveTextContent(t('m01.control.clear'));
    await userEvent.click(screen.getByRole('button', { name: changeHold() }));
    const form = screen.getByRole('form', { name: changeHold() });
    const submit = within(form).getByRole('button', { name: t('m01.flags.submit') });
    expect(within(form).getByLabelText(t('m01.flags.from'))).toHaveAttribute('max', todayIso());
    await userEvent.type(within(form).getByLabelText(t('m01.flags.from')), todayIso());
    await userEvent.type(within(form).getByLabelText(t('common.reason')), 'Salary release order');
    expect(submit).toBeDisabled();
    await attachOrder(form);
    await userEvent.click(submit);
    expect(await screen.findByText(t('m01.control.submitted'))).toBeTruthy();
    const sent = calls.filter((c) => c.method === 'POST').at(-1)?.body as FormData;
    expect(JSON.parse(await (sent.get('flag') as Blob).text())).toEqual({
      flagCode: 'HOLD',
      value: 'Y',
      effectiveFrom: todayIso(),
      reason: 'Salary release order',
    });
    expect((sent.get('file') as File).name).toBe('order.pdf');
    expect(screen.queryByRole('form', { name: changeHold() })).toBeNull();
  });

  it('SRS-1-7: a refusal is shown in the form; cancel closes it', async () => {
    backend({
      'GET /v1/document-types': () => TYPES,
      'GET /v1/employees/100041/control-flags': () => FLAGS,
      'POST /v1/employees/100041/control-flags': () => problem(422, 'EMP-422-CONTROL', ['NO_CHANGE']),
    });
    wrap(<ControlFlags eis="100041" />, me(['M-01:VIEW', 'M-01:FLAG']));
    await userEvent.click(await screen.findByRole('button', { name: changeHold() }));
    const form = screen.getByRole('form', { name: changeHold() });
    await userEvent.type(within(form).getByLabelText(t('m01.flags.from')), todayIso());
    await userEvent.type(within(form).getByLabelText(t('common.reason')), 'Order');
    await attachOrder(form);
    await userEvent.click(within(form).getByRole('button', { name: t('m01.flags.submit') }));
    expect(await within(form).findByRole('alert')).toHaveTextContent(
      t('error.EMP-422-CONTROL', ['NO_CHANGE']),
    );
    await userEvent.click(within(form).getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByRole('form', { name: changeHold() })).toBeNull();
  });

  it('SRS-1-7: a viewer sees the flags without a change action', async () => {
    backend({ 'GET /v1/employees/100041/control-flags': () => FLAGS });
    wrap(<ControlFlags eis="100041" />, me(['M-01:VIEW']));
    expect(await screen.findByText(t('m01.control.HOLD'))).toBeTruthy();
    expect(screen.queryByRole('button', { name: changeHold() })).toBeNull();
  });

  it('CLR-17: a later date, a date before joining, or a missing reason is not sendable', () => {
    const base = {
      flagCode: 'HOLD',
      value: 'Y',
      reason: 'Order',
      joiningDate: '2010-06-01',
      today: '2026-10-31',
    };
    expect(controlProposalComplete({ ...base, effectiveFrom: '2026-10-31' })).toBe(true);
    expect(controlProposalComplete({ ...base, effectiveFrom: '2026-11-01' })).toBe(false);
    expect(controlProposalComplete({ ...base, effectiveFrom: '2010-05-31' })).toBe(false);
    expect(controlProposalComplete({ ...base, effectiveFrom: '2026-10-01', reason: ' ' })).toBe(false);
  });
});
