import { fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { LabourMaster } from '@/modules/labour/components/LabourMaster';
import type { CorrectionEntry, MemberEntry } from '@/modules/labour/api/labourApi';
import {
  correctionBlocked,
  emptyEntry,
  entryBlocked,
  toAmendRequest,
} from '@/modules/labour/business/labourRules';
import { backend, me, paged, problem, sent, t, wrap } from './support/harness';

/**
 * M-02 name / date-of-birth correction, amendment and Casual Labour (plan 20 phase 20e; SRS-2-59,
 * SRS-2-30, SRS-G-55; D20-18).
 */

afterEach(() => vi.unstubAllGlobals());

const label = (key: string) => new RegExp(`^${t(key).replace(/[.*+?^${}()|[\]\\/]/g, '\\$&')}`);

const OPTIONS = {
  depots: [{ id: 7, code: 'DP-DL01', name: 'Depot Okhla', family: null }],
  designations: [{ id: 3, code: 'HL', name: 'Handling Labour', family: 'DEPT_LABOUR' }],
};

const ROW = {
  employeeId: 11,
  labourCode: 'DPT-000001',
  name: 'Ram Kumar',
  payrollType: 'DEPT_LABOUR',
  designation: 'HL - Handling Labour',
  depot: 'DP-DL01 - Depot Okhla',
  status: 'ACTIVE',
  joiningDate: '2010-07-01',
  retirementDate: null,
  panMasked: null,
  bankAccountMasked: null,
  casualLabour: true,
};

const TODAY = '2026-10-28';

/** The member as it stands (SRS-2-30), PAN, account and mobile masked by the server. */
const DETAIL = {
  employeeId: 11,
  labourCode: 'DPT-000001',
  payrollType: 'DEPT_LABOUR',
  firstName: 'Ram',
  lastName: 'Kumar',
  fatherName: 'Shyam Lal',
  gender: 'M',
  birthDate: '1980-02-01',
  joiningDate: '2010-07-01',
  designationId: 3,
  depotLocationId: 7,
  panMasked: '******234K',
  ifsc: 'SBIN0DEMO01',
  bankAccountMasked: '******2345',
  mobileMasked: '******0001',
  pfApplicable: true,
  cpfCode: 'DL/77',
  uanNumber: '100200300400',
  casualLabour: true,
};

function correction(over: Partial<CorrectionEntry> = {}): CorrectionEntry {
  return {
    type: 'NAME',
    firstName: 'Ram Kishan',
    lastName: 'Yadav',
    birthDate: '',
    reasonCode: 'AS_PER_SERVICE_BOOK',
    reasonText: '',
    orderReference: '',
    orderDate: '2026-10-27',
    issuingAuthority: 'Area Manager, Delhi',
    effectiveDate: TODAY,
    document: new File(['%PDF-1.7'], 'page.pdf', { type: 'application/pdf' }),
    ...over,
  };
}

function routes(extra: Record<string, (url: string) => unknown> = {}) {
  return backend({
    'GET /v1/labour/options': () => OPTIONS,
    'GET /v1/labour/members?': (url) => paged([ROW], 1, url),
    'GET /v1/labour/members/11': () => DETAIL,
    ...extra,
  });
}

describe('M-02 correction rules', () => {
  it('SRS-2-59: the name or date of birth, the document particulars and the file', () => {
    expect(correctionBlocked(correction(), TODAY)).toBeNull();
    expect(correctionBlocked(correction({ firstName: '9am' }), TODAY)).toBe('m02.why.name');
    expect(correctionBlocked(correction({ lastName: 'X$' }), TODAY)).toBe('m02.why.name');
    expect(correctionBlocked(correction({ type: 'DOB', birthDate: '' }), TODAY)).toBe('m02.why.birth');
    expect(correctionBlocked(correction({ type: 'DOB', birthDate: '2026-10-29' }), TODAY)).toBe(
      'm02.why.birth',
    );
    expect(correctionBlocked(correction({ type: 'DOB', birthDate: '1991-03-10' }), TODAY)).toBeNull();
    expect(correctionBlocked(correction({ orderDate: '2026-10-29' }), TODAY)).toBe('m02.why.document');
    expect(correctionBlocked(correction({ issuingAuthority: ' ' }), TODAY)).toBe('m02.why.document');
    expect(correctionBlocked(correction({ reasonCode: 'AS_PER_ORDER' }), TODAY)).toBe('m02.why.document');
    expect(
      correctionBlocked(correction({ reasonCode: 'AS_PER_ORDER', orderReference: 'EST/12' }), TODAY),
    ).toBeNull();
    expect(correctionBlocked(correction({ reasonText: 'नाम' }), TODAY)).toBe('m02.why.reason');
    expect(correctionBlocked(correction({ effectiveDate: '' }), TODAY)).toBe('m02.why.effective');
    expect(correctionBlocked(correction({ document: null }), TODAY)).toBe('m02.why.file');
  });

  it('SRS-2-59: an amendment keeps the name, date of birth, designation and depot', () => {
    const e: MemberEntry = {
      ...emptyEntry(),
      firstName: '',
      joiningDate: '2010-07-01',
      ifsc: 'SBIN0DEMO01',
      bankAccount: '0000012345',
      casualLabour: true,
      reason: 'Mobile corrected',
    };
    expect(entryBlocked(e, TODAY)).toBe('m02.why.name');
    expect(entryBlocked(e, TODAY, true)).toBeNull();
    const original: MemberEntry = {
      ...e,
      firstName: 'Ram',
      birthDate: '1980-02-01',
      casualLabour: false,
      reason: '',
    };
    const body = toAmendRequest(
      { ...e, designationId: 4, depotLocationId: 8, payrollType: 'DPS_LABOUR' },
      original,
    );
    expect(body).not.toHaveProperty('firstName');
    expect(body).not.toHaveProperty('birthDate');
    expect(body).not.toHaveProperty('designationId');
    expect(body).not.toHaveProperty('depotLocationId');
    expect(body).not.toHaveProperty('payrollType');
    expect(body).toEqual({ casualLabour: true, reason: 'Mobile corrected' });
  });

  it('SRS-2-30: an amendment carries only the changed fields; nothing changed is nothing to send', () => {
    const original: MemberEntry = {
      ...emptyEntry(),
      firstName: 'Ram',
      joiningDate: '2010-07-01',
      fatherName: 'Shyam Lal',
      gender: 'M',
      ifsc: 'SBIN0DEMO01',
      pfApplicable: true,
      cpfCode: 'DL/77',
      uanNumber: '100200300400',
    };
    // Unchanged: no field and no reason.
    expect(toAmendRequest({ ...original, reason: 'Nothing' }, original)).toEqual({});
    // The masked PAN, account and mobile left empty are kept (not sent).
    expect(toAmendRequest({ ...original, reason: 'Nothing' }, original)).not.toHaveProperty('panNumber');
    // A new account only: PAN, PF, CPF code and UAN are not sent, so the server keeps them.
    expect(
      toAmendRequest({ ...original, bankAccount: ' 0000099999 ', reason: ' New bank ' }, original),
    ).toEqual({
      bankAccount: '0000099999',
      reason: 'New bank',
    });
    // A cleared optional field is sent empty (cleared on the server).
    expect(toAmendRequest({ ...original, fatherName: '  ', reason: 'Cleared' }, original)).toEqual({
      fatherName: '',
      reason: 'Cleared',
    });
    // PF switched off: the flag and the CPF code (now none) go together.
    expect(toAmendRequest({ ...original, pfApplicable: false, reason: 'No PF' }, original)).toEqual({
      pfApplicable: false,
      cpfCode: '',
      reason: 'No PF',
    });
    // Same value retyped in another case is no change.
    expect(toAmendRequest({ ...original, ifsc: 'sbin0demo01', reason: 'x' }, original)).toEqual({});
    // SRS-2-30: an empty account on an amendment keeps the one in force; a bad one is refused.
    const amend = { ...original, reason: 'Kept' };
    expect(entryBlocked(amend, TODAY, true)).toBeNull();
    expect(entryBlocked({ ...amend, bankAccount: '12' }, TODAY, true)).toBe('m02.why.account');
    expect(entryBlocked(amend, TODAY)).toBe('m02.why.birth');
  });
});

describe('M-02 name / date-of-birth correction', () => {
  it('SRS-2-59: HR corrects the name as per the service book with the page attached', async () => {
    const calls = routes({
      'POST /v1/labour/members/11/corrections': () => ({
        status: 202,
        body: { id: 90, status: 'PENDING_APPROVAL' },
      }),
    });
    wrap(<LabourMaster />, me(['M-02:VIEW', 'M-02:EDIT']));
    const table = await screen.findByRole('table', { name: t('screen.M-02') });
    await userEvent.click(within(table).getByRole('button', { name: t('action.m02.correct') }));
    expect(screen.getByText(t('m02.correctionOf', ['DPT-000001']))).toBeTruthy();
    const send = screen.getByRole('button', { name: t('action.m02.send') });
    expect(send.getAttribute('title')).toBe(t('m02.why.document'));
    const first = screen.getByLabelText(label('m02.newFirstName'));
    await userEvent.clear(first);
    await userEvent.type(first, 'Ram Kishan');
    await userEvent.type(screen.getByLabelText(label('m02.newLastName')), ' Yadav');
    await userEvent.type(screen.getByLabelText(label('m02.documentAuthority')), 'Area Manager, Delhi');
    expect(send.getAttribute('title')).toBe(t('m02.why.file'));
    await userEvent.upload(
      screen.getByLabelText(label('m02.documentFile')),
      new File(['%PDF-1.7'], 'page.pdf', { type: 'application/pdf' }),
    );
    expect(send).toBeEnabled();
    await userEvent.click(send);
    expect(await screen.findByText(t('m02.correctionSent'))).toBeTruthy();
    const form = sent(calls, 'POST', '/corrections').at(-1) as FormData;
    expect(form.get('type')).toBe('NAME');
    expect(form.get('firstName')).toBe('Ram Kishan');
    expect(form.get('lastName')).toBe('Kumar Yadav');
    expect(form.get('reasonCode')).toBe('AS_PER_SERVICE_BOOK');
    expect(form.get('issuingAuthority')).toBe('Area Manager, Delhi');
    expect(form.get('document')).toBeInstanceOf(File);
    expect(form.get('birthDate')).toBeNull();
    expect(screen.queryByText(t('m02.correctionOf', ['DPT-000001']))).toBeNull();
  });

  it('SRS-2-59: a date of birth by order shows the recalculation note and the refusal', async () => {
    const calls = routes({
      'POST /v1/labour/members/11/corrections': () =>
        problem(503, 'LBR-503-NOT-CONFIGURED', ['RETIREMENT_AGE / EPS_CUTOFF_AGE']),
    });
    wrap(<LabourMaster />, me(['M-02:VIEW', 'M-02:EDIT']));
    const table = await screen.findByRole('table', { name: t('screen.M-02') });
    await userEvent.click(within(table).getByRole('button', { name: t('action.m02.correct') }));
    await userEvent.selectOptions(screen.getByLabelText(label('m02.correctionType')), 'DOB');
    expect(screen.getByText(t('m02.dobNote'))).toBeTruthy();
    expect(screen.queryByLabelText(label('m02.newFirstName'))).toBeNull();
    fireEvent.change(screen.getByLabelText(label('m02.newBirthDate')), { target: { value: '1991-03-10' } });
    await userEvent.selectOptions(screen.getByLabelText(label('m02.correctionReason')), 'AS_PER_ORDER');
    await userEvent.type(screen.getByLabelText(label('m02.documentAuthority')), 'GM (Region)');
    await userEvent.type(screen.getByLabelText(label('m02.correctionRemarks')), 'Matric certificate');
    await userEvent.upload(
      screen.getByLabelText(label('m02.documentFile')),
      new File(['%PDF-1.7'], 'order.pdf', { type: 'application/pdf' }),
    );
    const send = screen.getByRole('button', { name: t('action.m02.send') });
    expect(send.getAttribute('title')).toBe(t('m02.why.document'));
    await userEvent.type(screen.getByLabelText(label('m02.documentReference')), 'EST/DOB/12');
    fireEvent.change(screen.getByLabelText(label('m02.documentDate')), { target: { value: '2026-09-30' } });
    fireEvent.change(screen.getByLabelText(label('m02.correctionEffective')), {
      target: { value: '2026-10-01' },
    });
    await userEvent.click(send);
    expect(await screen.findByRole('alert')).toBeTruthy();
    const form = sent(calls, 'POST', '/corrections').at(-1) as FormData;
    expect(form.get('birthDate')).toBe('1991-03-10');
    expect(form.get('orderReference')).toBe('EST/DOB/12');
    expect(form.get('orderDate')).toBe('2026-09-30');
    expect(form.get('effectiveDate')).toBe('2026-10-01');
    expect(form.get('reasonText')).toBe('Matric certificate');
    expect(form.get('firstName')).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByText(t('m02.correctionOf', ['DPT-000001']))).toBeNull();
  });

  it('SRS-2-59: a viewer has no correction', async () => {
    routes();
    wrap(<LabourMaster />, me(['M-02:VIEW']));
    const table = await screen.findByRole('table', { name: t('screen.M-02') });
    const correct = within(table).getByRole('button', { name: t('action.m02.correct') });
    expect(correct).toBeDisabled();
    expect(correct.getAttribute('title')).toBe(t('reason.hrOnly'));
  });
});

describe('M-02 amendment and Casual Labour', () => {
  it('SRS-G-55 / SRS-2-59: an amendment keeps the name and date of birth and sets Casual Labour', async () => {
    const calls = routes({
      'POST /v1/labour/members/11/amendments': () => ({
        status: 202,
        body: { id: 91, status: 'PENDING_APPROVAL' },
      }),
    });
    wrap(<LabourMaster />, me(['M-02:VIEW', 'M-02:EDIT']));
    const table = await screen.findByRole('table', { name: t('screen.M-02') });
    expect(within(table).getByText(t('m02.casual'))).toBeTruthy();
    await userEvent.click(within(table).getByRole('button', { name: t('action.m02.amend') }));
    expect(screen.getByText(t('m02.keptOnAmend'))).toBeTruthy();
    expect(screen.getByLabelText(label('m02.firstName'))).toBeDisabled();
    expect(screen.getByLabelText(label('m02.birthDate'))).toBeDisabled();
    const casual = screen.getByLabelText(label('m02.casualLabour')) as HTMLInputElement;
    expect(casual.checked).toBe(true);
    await userEvent.click(casual);
    await userEvent.type(screen.getByLabelText(label('m02.reason')), 'Casual flag withdrawn');
    await userEvent.click(screen.getByRole('button', { name: t('action.m02.send') }));
    expect(await screen.findByText(t('m02.sent'))).toBeTruthy();
    const body = sent(calls, 'POST', '/amendments').at(-1) as Record<string, unknown>;
    // SRS-2-30: only the changed flag and the reason; PAN, PF, CPF code and UAN stay as they are.
    expect(body).toEqual({ casualLabour: false, reason: 'Casual flag withdrawn' });
  });
});
