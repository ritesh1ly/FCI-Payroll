import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AllowanceFlags } from '@/modules/employee/components/AllowanceFlags';
import { backend, me, paged, problem, t, wrap } from './support/harness';

/**
 * M-01 Payroll allowance flags (plan 16 phase 16d-2; SRS-1-4, SRS-1-11): the flags with their
 * value today and history; Establishment/HR proposes a change sent to the Payroll Checker; a flag
 * waiting for the Checker cannot be changed and says why; a viewer has no change action.
 */

afterEach(() => vi.unstubAllGlobals());

const TYPES = [{ code: 'OTHER', name: 'Other document', referenceRequired: false }];

/** SRS-1-26: the supporting document of a proposal (type, date, authority, file). */
async function attachDocument(form: HTMLElement) {
  await userEvent.selectOptions(within(form).getByLabelText(t('ord.type')), 'OTHER');
  await userEvent.type(within(form).getByLabelText(t('ord.date')), '2026-01-15');
  await userEvent.type(within(form).getByLabelText(t('ord.authority')), 'Establishment, Delhi');
  await userEvent.upload(
    within(form).getByLabelText(t('ord.file')),
    new File(['%PDF-1.7'], 'order.pdf', { type: 'application/pdf' }),
  );
}

/** The proposal part of the multipart request, and the document's particulars. */
async function proposalSent(calls: { method: string; url: string; body?: unknown }[]) {
  const form = calls.filter((c) => c.method === 'POST' && c.url.includes('/allowance-flags')).at(-1)
    ?.body as FormData;
  return {
    flag: JSON.parse(await (form.get('flag') as Blob).text()) as Record<string, string>,
    documentType: form.get('documentType'),
    orderDate: form.get('orderDate'),
    issuingAuthority: form.get('issuingAuthority'),
    file: (form.get('file') as File).name,
  };
}

const FLAGS = {
  eis: '100041',
  joiningDate: '2010-06-01',
  flags: [
    { code: 'CCA', label: 'City Compensatory Allowance', value: 'Y', since: '2026-04-01', pending: false },
    { code: 'NPA', label: 'Non-Practising Allowance', value: 'N', since: null, pending: false },
    { code: 'TRANSPORT', label: 'Transport Allowance', value: 'N', since: null, pending: true },
  ],
};

const HISTORY = [
  {
    code: 'CCA',
    value: 'Y',
    effectiveFrom: '2026-04-01',
    effectiveTo: '2027-03-31',
    reason: 'Posted to Delhi',
    changedBy: 'hr.delhi',
    changedAt: '2026-04-02T05:00:00Z',
    approvalId: 9,
  },
];

describe('M-01 allowance flags', () => {
  it('SRS-1-11: establishment proposes a flag; it is sent to the checker', async () => {
    const calls = backend({
      'GET /v1/document-types': () => TYPES,
      'GET /v1/employees/100041/allowance-flags/history': (url) => paged(HISTORY, 1, url),
      'GET /v1/employees/100041/allowance-flags': () => FLAGS,
      'POST /v1/employees/100041/allowance-flags': () => ({
        status: 202,
        body: { approvalId: 77, status: 'PENDING_APPROVAL' },
      }),
    });
    wrap(<AllowanceFlags eis="100041" />, me(['M-01:VIEW', 'M-01:FLAG']));
    expect(await screen.findByText('Posted to Delhi')).toBeTruthy();
    expect(screen.getByText(t('m01.flags.pending'))).toBeTruthy();
    const transport = screen.getByRole('button', { name: t('m01.flags.change', ['Transport Allowance']) });
    expect(transport).toBeDisabled();
    expect(transport).toHaveAttribute('title', t('m01.flags.why.pending'));
    await userEvent.click(
      screen.getByRole('button', { name: t('m01.flags.change', ['Non-Practising Allowance']) }),
    );
    const form = screen.getByRole('form', { name: t('m01.flags.change', ['Non-Practising Allowance']) });
    const submit = within(form).getByRole('button', { name: t('m01.flags.submit') });
    expect(submit).toBeDisabled();
    await userEvent.type(within(form).getByLabelText(t('m01.flags.from')), '2026-10-01');
    await userEvent.type(within(form).getByLabelText(t('common.reason')), 'Medical officer');
    // SRS-1-26: the current value is shown, and nothing is sent without the supporting document.
    expect(within(form).getByText(t('m01.flags.current'))).toBeTruthy();
    expect(submit).toBeDisabled();
    await attachDocument(form);
    await userEvent.click(submit);
    expect(await screen.findByText(t('m01.flags.submitted', ['Non-Practising Allowance', 77]))).toBeTruthy();
    expect(await proposalSent(calls)).toEqual({
      flag: {
        flagCode: 'NPA',
        value: 'Y',
        effectiveFrom: '2026-10-01',
        effectiveTo: null,
        reason: 'Medical officer',
      },
      documentType: 'OTHER',
      orderDate: '2026-01-15',
      issuingAuthority: 'Establishment, Delhi',
      file: 'order.pdf',
    });
    expect(
      screen.queryByRole('form', { name: t('m01.flags.change', ['Non-Practising Allowance']) }),
    ).toBeNull();
  });

  it('SRS-1-11: a refusal is shown; cancel closes the form; switching a Yes flag proposes No', async () => {
    const calls = backend({
      'GET /v1/document-types': () => TYPES,
      'GET /v1/employees/100041/allowance-flags/history': (url) => paged([], 0, url),
      'GET /v1/employees/100041/allowance-flags': () => FLAGS,
      'POST /v1/employees/100041/allowance-flags': () => problem(422, 'EMP-422-FLAG', ['BEFORE_JOINING']),
    });
    wrap(<AllowanceFlags eis="100041" />, me(['M-01:VIEW', 'M-01:FLAG']));
    expect(await screen.findByText(t('m01.flags.noHistory'))).toBeTruthy();
    await userEvent.click(
      screen.getByRole('button', { name: t('m01.flags.change', ['City Compensatory Allowance']) }),
    );
    const form = screen.getByRole('form', { name: t('m01.flags.change', ['City Compensatory Allowance']) });
    expect(within(form).getByLabelText(t('m01.flags.requested'))).toHaveValue('N');
    await userEvent.selectOptions(within(form).getByLabelText(t('m01.flags.requested')), 'Y');
    await userEvent.selectOptions(within(form).getByLabelText(t('m01.flags.requested')), 'N');
    await userEvent.type(within(form).getByLabelText(t('m01.flags.from')), '2026-10-01');
    await userEvent.type(within(form).getByLabelText(t('common.reason')), 'Transferred');
    await attachDocument(form);
    // US-GAP-39: an end date before the effective date is not sent; a later one is.
    const submit = within(form).getByRole('button', { name: t('m01.flags.submit') });
    await userEvent.type(within(form).getByLabelText(t('m01.flags.to')), '2026-09-30');
    expect(within(form).getByText(t('m01.flags.why.to'))).toBeTruthy();
    expect(submit).toBeDisabled();
    await userEvent.clear(within(form).getByLabelText(t('m01.flags.to')));
    await userEvent.type(within(form).getByLabelText(t('m01.flags.to')), '2027-03-31');
    expect(within(form).queryByText(t('m01.flags.why.to'))).toBeNull();
    await userEvent.click(submit);
    expect(await within(form).findByRole('alert')).toHaveTextContent(
      t('error.EMP-422-FLAG', ['BEFORE_JOINING']),
    );
    expect((await proposalSent(calls)).flag).toEqual({
      flagCode: 'CCA',
      value: 'N',
      effectiveFrom: '2026-10-01',
      effectiveTo: '2027-03-31',
      reason: 'Transferred',
    });
    await userEvent.click(within(form).getByRole('button', { name: t('common.cancel') }));
    expect(
      screen.queryByRole('form', { name: t('m01.flags.change', ['City Compensatory Allowance']) }),
    ).toBeNull();
  });

  it('SRS-1-4: a viewer sees the flags and history without a change action; an unknown history code shows as is', async () => {
    backend({
      'GET /v1/employees/100041/allowance-flags/history': (url) =>
        paged([{ ...HISTORY[0], code: 'OLD_FLAG', effectiveTo: null }], 1, url),
      'GET /v1/employees/100041/allowance-flags': () => FLAGS,
    });
    wrap(<AllowanceFlags eis="100041" />, me(['M-01:VIEW']));
    expect(await screen.findByText('OLD_FLAG')).toBeTruthy();
    expect(
      screen.queryByRole('button', { name: t('m01.flags.change', ['Non-Practising Allowance']) }),
    ).toBeNull();
    expect(screen.getAllByText(t('m01.flags.N')).length).toBeGreaterThan(0);
  });
});
