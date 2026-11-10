import { fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { LabourMaster } from '@/modules/labour/components/LabourMaster';
import type { NamedPerson, ReinstatementEntry } from '@/modules/labour/api/labourApi';
import { nominationBlocked, reinstatementBlocked } from '@/modules/labour/business/labourRules';
import { backend, me, paged, problem, referenceLists, sent, t, wrap } from './support/harness';

/**
 * M-02 reinstatement and nominees (plan 22 phase 22c; NWNP Reinstate Details, Nominee &amp; Dependent
 * Details, BR-FNF-014; D22-7, D22-8): a separated member is reinstated with its order; nominees'
 * shares add to 100; the Payroll Checker approves both.
 */

afterEach(() => vi.unstubAllGlobals());

const label = (key: string, args: unknown[] = []) =>
  new RegExp(`^${t(key, args as string[]).replace(/[.*+?^${}()|[\]\\/]/g, '\\$&')}`);

const TODAY = '2026-10-28';

const ROW = {
  employeeId: 33,
  labourCode: 'L30703',
  name: 'Dinesh Mahto',
  payrollType: 'NWNP_LABOUR',
  designation: 'NHL - Handling Labour (NWNP)',
  depot: 'DP-DL01 - Depot Okhla',
  status: 'SEPARATED',
  joiningDate: '2011-07-01',
  retirementDate: null,
  panMasked: null,
  bankAccountMasked: null,
};

const REFERENCE = referenceLists({
  NOMINEE_RELATIONSHIP: [
    ['SPOUSE', 'Spouse'],
    ['SON', 'Son'],
  ],
});

function entry(over: Partial<ReinstatementEntry> = {}): ReinstatementEntry {
  return {
    date: TODAY,
    orderReference: 'EST/RI/7',
    orderDate: '2026-10-27',
    issuingAuthority: 'Area Manager',
    reason: 'Appellate order',
    document: new File(['%PDF'], 'order.pdf', { type: 'application/pdf' }),
    ...over,
  };
}

const person = (over: Partial<NamedPerson> = {}): NamedPerson => ({
  name: 'Sita',
  relationship: 'SPOUSE',
  birthDate: null,
  share: 100,
  nominee: true,
  ...over,
});

describe('M-02 reinstatement and nominee rules', () => {
  // SC-22-0040
  it('reinstateDetails: the date, the order and its file, and the reason', () => {
    expect(reinstatementBlocked(entry(), TODAY)).toBeNull();
    expect(reinstatementBlocked(entry({ date: '' }), TODAY)).toBe('m02.why.reinstateDate');
    expect(reinstatementBlocked(entry({ orderDate: '' }), TODAY)).toBe('m02.why.document');
    expect(reinstatementBlocked(entry({ orderDate: '2026-10-29' }), TODAY)).toBe('m02.why.document');
    expect(reinstatementBlocked(entry({ issuingAuthority: ' ' }), TODAY)).toBe('m02.why.document');
    expect(reinstatementBlocked(entry({ issuingAuthority: 'प्राधिकारी' }), TODAY)).toBe('m02.why.document');
    expect(reinstatementBlocked(entry({ orderReference: 'आदेश' }), TODAY)).toBe('m02.why.document');
    expect(reinstatementBlocked(entry({ orderReference: '' }), TODAY)).toBeNull();
    expect(reinstatementBlocked(entry({ reason: '' }), TODAY)).toBe('m02.why.reason');
    expect(reinstatementBlocked(entry({ document: null }), TODAY)).toBe('m02.why.file');
  });

  // SC-22-0042
  it('nomineeDetails: someone named, each well formed, the shares adding to 100', () => {
    expect(nominationBlocked([person()], [], 'Form 2', TODAY)).toBeNull();
    expect(nominationBlocked([], [person({ share: null, nominee: false })], 'Form 2', TODAY)).toBeNull();
    expect(nominationBlocked([], [], 'Form 2', TODAY)).toBe('m02.why.nobody');
    expect(nominationBlocked([person({ name: ' ' })], [], 'r', TODAY)).toBe('m02.why.personName');
    expect(nominationBlocked([person({ name: 'n'.repeat(151) })], [], 'r', TODAY)).toBe('m02.why.personName');
    expect(nominationBlocked([person({ name: 'सीता' })], [], 'r', TODAY)).toBe('m02.why.personName');
    expect(nominationBlocked([person({ relationship: '' })], [], 'r', TODAY)).toBe('m02.why.relationship');
    expect(nominationBlocked([person({ birthDate: '2026-10-29' })], [], 'r', TODAY)).toBe('m02.why.birth');
    expect(nominationBlocked([person({ birthDate: '1990-01-01' })], [], 'r', TODAY)).toBeNull();
    expect(nominationBlocked([person({ share: null })], [], 'r', TODAY)).toBe('m02.why.share');
    expect(nominationBlocked([person({ share: 0 })], [], 'r', TODAY)).toBe('m02.why.share');
    expect(nominationBlocked([person({ share: 100.5 })], [], 'r', TODAY)).toBe('m02.why.share');
    expect(nominationBlocked([person({ share: 33.333 })], [], 'r', TODAY)).toBe('m02.why.share');
    expect(
      nominationBlocked([person({ share: 33.33 }), person({ share: 66.67 })], [], 'r', TODAY),
    ).toBeNull();
    expect(nominationBlocked([person({ share: 60 }), person({ share: 30 })], [], 'r', TODAY)).toBe(
      'm02.why.shareTotal',
    );
    expect(nominationBlocked([person()], [], ' ', TODAY)).toBe('m02.why.reason');
  });
});

function routes(extra: Record<string, (url: string) => unknown> = {}) {
  return backend({
    'GET /v1/labour/options': () => ({ depots: [], designations: [] }),
    'GET /v1/labour/members?': (url) =>
      paged([ROW, { ...ROW, employeeId: 34, labourCode: 'L30704', status: 'ACTIVE' }], 2, url),
    'GET /v1/reference/lists': () => REFERENCE,
    'GET /v1/labour/members/33/nominations': () => ({
      nominees: [{ name: 'Sita Mahto', relationship: 'SPOUSE', birthDate: null, share: 100, nominee: true }],
      dependents: [
        { name: 'Kiran Mahto', relationship: 'SON', birthDate: '2015-01-01', share: null, nominee: false },
      ],
      pending: false,
    }),
    ...extra,
  });
}

async function rowOf(code: string) {
  const table = await screen.findByRole('table', { name: t('screen.M-02') });
  return within(table).getByText(code).closest('tr') as HTMLElement;
}

describe('M-02 reinstatement', () => {
  it('reinstateDetails: HR reinstates a separated member with the order; an active one has no Reinstate', async () => {
    const calls = routes({
      'POST /v1/labour/members/33/reinstatements': () => ({
        status: 202,
        body: { id: 90, status: 'PENDING_APPROVAL' },
      }),
    });
    wrap(<LabourMaster />, me(['M-02:VIEW', 'M-02:EDIT']));
    const active = await rowOf('L30704');
    expect(within(active).queryByRole('button', { name: t('action.m02.reinstate') })).toBeNull();
    await userEvent.click(
      within(await rowOf('L30703')).getByRole('button', { name: t('action.m02.reinstate') }),
    );
    const send = screen.getByRole('button', { name: t('action.m02.send') });
    expect(send).toBeDisabled();
    await userEvent.type(screen.getByLabelText(label('m02.orderReference')), 'EST/RI/7');
    fireEvent.change(screen.getByLabelText(label('m02.orderDate')), { target: { value: '2026-10-01' } });
    await userEvent.type(screen.getByLabelText(label('m02.issuingAuthority')), 'Area Manager');
    await userEvent.type(screen.getByLabelText(label('m09.changeReason')), 'Appellate order');
    expect(send.getAttribute('title')).toBe(t('m02.why.file'));
    await userEvent.upload(
      screen.getByLabelText(label('m02.reinstateOrder')),
      new File(['%PDF'], 'order.pdf', { type: 'application/pdf' }),
    );
    fireEvent.change(screen.getByLabelText(label('m02.reinstateDate')), { target: { value: '2026-10-15' } });
    expect(send).toBeEnabled();
    await userEvent.click(send);
    expect(await screen.findByText(t('m02.reinstateSent'))).toBeTruthy();
    const form = sent(calls, 'POST', '/reinstatements').at(-1) as FormData;
    expect(form.get('date')).toBe('2026-10-15');
    expect(form.get('orderReference')).toBe('EST/RI/7');
    expect((form.get('document') as File).name).toBe('order.pdf');
  });

  it('reinstateDetails: a refusal is shown and kept; cancel closes; a viewer cannot reinstate', async () => {
    routes({
      'POST /v1/labour/members/33/reinstatements': () => problem(422, 'LBR-422-ENTRY', ['PENDING']),
    });
    const view = wrap(<LabourMaster />, me(['M-02:VIEW', 'M-02:EDIT']));
    await userEvent.click(
      within(await rowOf('L30703')).getByRole('button', { name: t('action.m02.reinstate') }),
    );
    fireEvent.change(screen.getByLabelText(label('m02.orderDate')), { target: { value: '2026-10-01' } });
    await userEvent.type(screen.getByLabelText(label('m02.issuingAuthority')), 'Area Manager');
    await userEvent.type(screen.getByLabelText(label('m09.changeReason')), 'Order');
    await userEvent.upload(
      screen.getByLabelText(label('m02.reinstateOrder')),
      new File(['%PDF'], 'o.pdf', { type: 'application/pdf' }),
    );
    await userEvent.click(screen.getByRole('button', { name: t('action.m02.send') }));
    expect(await screen.findByRole('alert')).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByText(t('m02.reinstatementOf', ['L30703']))).toBeNull();
    view.unmount();
    routes();
    wrap(<LabourMaster />, me(['M-02:VIEW']));
    const reinstate = within(await rowOf('L30703')).getByRole('button', { name: t('action.m02.reinstate') });
    expect(reinstate).toBeDisabled();
    expect(reinstate.getAttribute('title')).toBe(t('reason.hrOnly'));
  });
});

describe('M-02 nominees and dependents', () => {
  // SC-22-0042, SC-22-0043
  it('nomineeDetails: the ones in force read; HR records a new set adding to 100', async () => {
    const calls = routes({
      'POST /v1/labour/members/33/nominations': () => ({
        status: 202,
        body: { id: 91, status: 'PENDING_APPROVAL' },
      }),
    });
    wrap(<LabourMaster />, me(['M-02:VIEW', 'M-02:EDIT']));
    await userEvent.click(within(await rowOf('L30703')).getByRole('button', { name: t('m02.nominees') }));
    const table = await screen.findByRole('table', { name: t('m02.nominationsOf', ['L30703']) });
    expect(within(table).getByText('100%')).toBeTruthy();
    expect(within(table).getByText('SPOUSE - Spouse')).toBeTruthy();
    expect(within(table).getByText(t('m02.dependent'))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('action.m02.nominees') }));
    await userEvent.type(screen.getByLabelText(label('m02.nomineeName', [1])), 'Sita Mahto');
    await userEvent.selectOptions(screen.getByLabelText(label('m02.nomineeRelationship', [1])), 'SPOUSE');
    await userEvent.type(screen.getByLabelText(label('m02.nomineeShare', [1])), '60');
    await userEvent.click(screen.getByRole('button', { name: t('action.m02.addNominee') }));
    await userEvent.type(screen.getByLabelText(label('m02.nomineeName', [2])), 'Kiran Mahto');
    await userEvent.selectOptions(screen.getByLabelText(label('m02.nomineeRelationship', [2])), 'SON');
    await userEvent.type(screen.getByLabelText(label('m02.nomineeShare', [2])), '30');
    await userEvent.type(screen.getByLabelText(label('m09.changeReason')), 'Form 2');
    const send = screen.getByRole('button', { name: t('action.m02.send') });
    expect(send.getAttribute('title')).toBe(t('m02.why.shareTotal'));
    const share = screen.getByLabelText(label('m02.nomineeShare', [2]));
    await userEvent.clear(share);
    await userEvent.type(share, '40');
    // A third nominee row added by mistake is removed again.
    await userEvent.click(screen.getByRole('button', { name: t('action.m02.addNominee') }));
    expect(screen.getByLabelText(label('m02.nomineeName', [3]))).toBeTruthy();
    await userEvent.click(
      screen.getAllByRole('button', { name: t('action.m02.removePerson') }).at(-1) as HTMLElement,
    );
    expect(screen.queryByLabelText(label('m02.nomineeName', [3]))).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: t('action.m02.addDependent') }));
    await userEvent.type(screen.getByLabelText(label('m02.dependentName', [1])), 'Asha Mahto');
    await userEvent.selectOptions(screen.getByLabelText(label('m02.dependentRelationship', [1])), 'SON');
    fireEvent.change(screen.getByLabelText(label('m02.dependentBirth', [1])), {
      target: { value: '2016-02-02' },
    });
    await userEvent.click(screen.getByRole('button', { name: t('action.m02.addDependent') }));
    await userEvent.click(
      screen.getAllByRole('button', { name: t('action.m02.removePerson') }).at(-1) as HTMLElement,
    );
    expect(send).toBeEnabled();
    await userEvent.click(send);
    expect(await screen.findByText(t('m02.nominationSent'))).toBeTruthy();
    expect(sent(calls, 'POST', '/nominations').at(-1)).toEqual({
      nominees: [
        { name: 'Sita Mahto', relationship: 'SPOUSE', birthDate: null, share: 60, nominee: true },
        { name: 'Kiran Mahto', relationship: 'SON', birthDate: null, share: 40, nominee: true },
      ],
      dependents: [
        { name: 'Asha Mahto', relationship: 'SON', birthDate: '2016-02-02', share: null, nominee: false },
      ],
      reason: 'Form 2',
    });
  });

  it('nomineeDetails: a waiting change blocks a new one; a viewer reads; a refusal is shown; cancel and close', async () => {
    routes({
      'GET /v1/labour/members/33/nominations': () => ({ nominees: [], dependents: [], pending: true }),
    });
    const view = wrap(<LabourMaster />, me(['M-02:VIEW', 'M-02:EDIT']));
    await userEvent.click(within(await rowOf('L30703')).getByRole('button', { name: t('m02.nominees') }));
    expect(await screen.findByText(t('m02.noNominees'))).toBeTruthy();
    const record = screen.getByRole('button', { name: t('action.m02.nominees') });
    expect(record).toBeDisabled();
    expect(record.getAttribute('title')).toBe(t('m02.nominationPending'));
    await userEvent.click(screen.getByRole('button', { name: t('common.close') }));
    expect(screen.queryByText(t('m02.noNominees'))).toBeNull();
    view.unmount();

    routes({
      'POST /v1/labour/members/33/nominations': () => problem(422, 'LBR-422-ENTRY', ['RELATIONSHIP']),
    });
    const second = wrap(<LabourMaster />, me(['M-02:VIEW', 'M-02:EDIT']));
    await userEvent.click(within(await rowOf('L30703')).getByRole('button', { name: t('m02.nominees') }));
    await userEvent.click(await screen.findByRole('button', { name: t('action.m02.nominees') }));
    await userEvent.type(screen.getByLabelText(label('m02.nomineeName', [1])), 'Sita');
    await userEvent.selectOptions(screen.getByLabelText(label('m02.nomineeRelationship', [1])), 'SPOUSE');
    fireEvent.change(screen.getByLabelText(label('m02.nomineeBirth', [1])), {
      target: { value: '1990-01-01' },
    });
    await userEvent.type(screen.getByLabelText(label('m02.nomineeShare', [1])), '100');
    await userEvent.type(screen.getByLabelText(label('m09.changeReason')), 'Form 2');
    await userEvent.click(screen.getByRole('button', { name: t('action.m02.send') }));
    expect(await screen.findByRole('alert')).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('common.cancel') }));
    expect(screen.getByRole('button', { name: t('action.m02.nominees') })).toBeTruthy();
    second.unmount();

    routes();
    wrap(<LabourMaster />, me(['M-02:VIEW']));
    await userEvent.click(within(await rowOf('L30703')).getByRole('button', { name: t('m02.nominees') }));
    const viewOnly = await screen.findByRole('button', { name: t('action.m02.nominees') });
    expect(viewOnly).toBeDisabled();
    expect(viewOnly.getAttribute('title')).toBe(t('reason.hrOnly'));
  });
});
