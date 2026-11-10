import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  LeaveEncashmentArrears,
  LeaveEventsScreen,
  LeaveParametersScreen,
  LeaveTypesScreen,
} from '@/modules/leave';
import { backend, exportedUrl, me, paged, referenceLists, sent, t, wrap } from './support/harness';

/**
 * Coverage of the leave screens' remaining paths (I-11, M-13, M-20, I-24; OD-121): the report filters sent to the
 * Leave Recovery Report (SRS-LV-23), the status tones, the M-20 actions per role (SRS-LV-14) and the I-24 order copy
 * of a Ready record (SRS-G-30) and a cancellation dismissed (SRS-G-37).
 */
afterEach(() => vi.unstubAllGlobals());

const REFERENCE = referenceLists({
  LEAVE_RECOVERY_EVENT: [['LWP', 'LWP']],
  LEAVE_RECOVERY_STATUS: [['POSTED', 'Recovered']],
  ORDER_COPY_DOCUMENT_TYPE: [
    ['CIRCULAR', 'Circular'],
    ['OTHER', 'Other'],
  ],
});

const event = (over: object = {}) => ({
  applicationId: 1,
  sanctionRef: 'HR/LV/2026/88410',
  eis: '881402',
  name: 'Anita Banerjee',
  office: 'DH001 - Delhi',
  leaveType: 'LWP',
  leaveTypeName: 'Leave Without Pay',
  from: '2026-10-05',
  to: '2026-10-08',
  days: 4,
  eventType: 'ADDITION',
  received: '2026-10-09T10:00:00',
  payEffect: 'FULL_DEDUCTION',
  status: 'APPLIED',
  appliedIn: '2026-10',
  rule: null,
  effectAmount: null,
  ...over,
});

describe('I-11 remaining paths', () => {
  it('SRS_LV_13_eachStatusHasItsToneAndAnEffectWithoutRuleIsShownAlone', async () => {
    backend({
      'GET /v1/reference/lists': () => REFERENCE,
      'GET /v1/leave-events/counts': () => ({ received: 3, applied: 1, backDated: 0, held: 1 }),
      'GET /v1/leave-events': () =>
        paged([
          event(),
          event({ applicationId: 2, sanctionRef: 'HR/LV/2', status: 'PENDING', payEffect: null }),
          event({ applicationId: 3, sanctionRef: 'HR/LV/3', status: 'HELD' }),
          event({ applicationId: 4, sanctionRef: 'HR/LV/4', status: 'BACK_DATED', effectAmount: -100 }),
        ]),
    });
    wrap(<LeaveEventsScreen />, ['I-11:VIEW']);
    const applied = (await screen.findByText('HR/LV/2026/88410')).closest('tr') as HTMLElement;
    expect(applied.querySelector('.pill')).toHaveClass('p-ok');
    expect(applied).toHaveTextContent(t('lv.effect.FULL_DEDUCTION'));
    expect(applied.querySelectorAll('td')[6]?.textContent).toBe(t('lv.effect.FULL_DEDUCTION'));
    const pending = screen.getByText('HR/LV/2').closest('tr') as HTMLElement;
    expect(pending.querySelector('.pill')).toHaveClass('p-warn');
    const held = screen.getByText('HR/LV/3').closest('tr') as HTMLElement;
    expect(held.querySelector('.pill')).toHaveClass('p-bad');
    expect(held).toHaveTextContent(t('i11.status.HELD'));
    // A back-dated event with no rule reads its status, not "back-dated under a rule".
    const backDated = screen.getByText('HR/LV/4').closest('tr') as HTMLElement;
    expect(backDated.querySelector('.pill')).toHaveClass('p-warn');
    expect(backDated).toHaveTextContent(t('i11.status.BACK_DATED'));
    expect(backDated).toHaveTextContent('100');
  });

  it('SRS_LV_23_theReportCarriesEveryFilterAndAClearedFilterIsLeftOut', async () => {
    backend({
      'GET /v1/reference/lists': () => REFERENCE,
      'GET /v1/leave-events/counts': () => ({ received: 1, applied: 1, backDated: 0, held: 0 }),
      'GET /v1/leave-events': () => paged([event()]),
    });
    const user = userEvent.setup();
    wrap(<LeaveEventsScreen />, ['I-11:VIEW', 'I-11:EXPORT']);
    await screen.findByText('HR/LV/2026/88410');
    await user.click(screen.getByRole('button', { name: t('i11w.report') }));
    await waitFor(() => expect(screen.getByTestId('i11-report')).toHaveAttribute('open'));
    fireEvent.change(screen.getByLabelText(t('i11w.from')), { target: { value: '2026-04' } });
    fireEvent.change(screen.getByLabelText(t('i11w.to')), { target: { value: '2026-09' } });
    await user.type(screen.getByLabelText(t('i11.leaveType')), ' ');
    await user.clear(screen.getByLabelText(t('i11.leaveType')));
    await user.type(screen.getByLabelText(t('i11.leaveType')), 'el');
    await user.type(screen.getByLabelText(t('m01.eis')), '  ');
    await screen.findByRole('option', { name: 'Recovered' });
    await user.selectOptions(screen.getByLabelText(t('i11.status')), 'POSTED');
    await user.selectOptions(screen.getByLabelText(t('i11.status')), '');
    await user.selectOptions(screen.getByLabelText(t('i11.status')), 'POSTED');
    await user.selectOptions(screen.getByLabelText(t('i11w.eventType')), 'LWP');
    await user.selectOptions(screen.getByLabelText(t('i11w.eventType')), '');
    const href = await exportedUrl('CSV', t('i11w.reportName'));
    expect(href).toContain('/v1/leave-events/recovery-report?');
    expect(href).toContain('fromMonth=202604');
    expect(href).toContain('toMonth=202609');
    expect(href).toContain('leaveType=EL');
    expect(href).toContain('status=POSTED');
    expect(href).not.toContain('eis=');
    expect(href).not.toContain('eventType=');
    // A month cleared leaves the report explained, never a report without its range.
    fireEvent.change(screen.getByLabelText(t('i11w.to')), { target: { value: '' } });
    expect(screen.getByRole('button', { name: t('i11w.reportName') })).toHaveAttribute(
      'data-guard',
      t('i11w.why.export'),
    );
  });
});

describe('M-13 remaining paths', () => {
  it('SRS_G_45_theClosedRegisterOpensTheFullList', async () => {
    backend({
      'GET /v1/leave-types/counts': () => ({ received: 0, encashable: 0, payReducing: 0, lastSync: null }),
      'GET /v1/leave-types': () => paged([]),
    });
    const user = userEvent.setup();
    wrap(<LeaveTypesScreen />, ['M-13:VIEW']);
    await screen.findByRole('heading', { name: t('m13w.title') });
    await user.click(within(screen.getByTestId('m13-register')).getByText(t('m13w.register')));
    await waitFor(() => expect(screen.getByTestId('m13-register')).toHaveAttribute('open'));
    // The register keeps its own note and, with nothing received, says so; its last sync reads as a dash.
    const register = screen.getByTestId('m13-register');
    expect(await within(register).findByText(t('m13.note'))).toBeInTheDocument();
    expect(await within(register).findByText(t('m13.none'))).toBeInTheDocument();
  });
});

const parameter = (over: object = {}) => ({
  id: 1,
  code: 'LWP_DIVISOR',
  leaveType: null,
  number: null,
  text: 'CALENDAR_DAYS',
  versionNo: 1,
  effectiveFrom: '2026-04-01',
  effectiveTo: null,
  status: 'ACTIVE',
  approvalId: 1,
  circularNumber: null,
  ...over,
});

describe('M-20 remaining paths', () => {
  const versions = [
    parameter(),
    parameter({ id: 2, code: 'ENCASH_PAY_CODES', text: null, number: null }),
    parameter({ id: 3, code: 'ENCASH_DIVISOR', text: null, number: 30, status: 'PENDING_APPROVAL' }),
  ];

  it('SRS_LV_14_aValueWithNeitherTextNorNumberIsADash_aPostingParameterSaysSo', async () => {
    backend({ 'GET /v1/leave-pay-parameters': () => paged(versions) });
    const user = userEvent.setup();
    wrap(<LeaveParametersScreen />, ['M-20:VIEW']);
    await screen.findByText('CALENDAR_DAYS');
    const codes = document.querySelector('[data-reference="ENCASH_PAY_CODES"]') as HTMLElement;
    expect(codes.querySelectorAll('td')[1]).toHaveTextContent('—');
    expect(codes.querySelectorAll('td')[2]).toHaveTextContent(t('m20w.posting'));
    await user.click(within(codes).getByRole('button', { name: new RegExp(t('common.view')) }));
    let dialog = screen.getByRole('dialog');
    expect(dialog).toHaveTextContent(t('m20w.posting'));
    await user.click(within(dialog).getByRole('button', { name: t('common.close') }));
    expect(screen.queryByRole('dialog')).toBeNull();
    // Only a proposal: nothing in force, the proposed value shown.
    const waiting = document.querySelector('[data-reference="ENCASH_DIVISOR"]') as HTMLElement;
    await user.click(within(waiting).getByRole('button', { name: new RegExp(t('common.view')) }));
    dialog = screen.getByRole('dialog');
    expect(dialog).toHaveTextContent('30');
    expect(dialog).toHaveTextContent('BR-LEAVE-008');
  });

  it('SRS_LV_14_theCheckerGoesToW01ForAProposalAndIsToldWhenNothingWaits', async () => {
    backend({ 'GET /v1/leave-pay-parameters': () => paged(versions) });
    const assign = vi.fn();
    vi.stubGlobal('location', { ...window.location, assign });
    const user = userEvent.setup();
    wrap(<LeaveParametersScreen />, ['M-20:VIEW', 'M-20:APPROVE']);
    await screen.findByText('CALENDAR_DAYS');
    const inForce = document.querySelector('[data-reference="LWP_DIVISOR"]') as HTMLElement;
    await user.click(within(inForce).getByRole('button', { name: '✔' }));
    expect(await screen.findByText(t('mst.nothingWaiting'))).toBeInTheDocument();
    expect(assign).not.toHaveBeenCalled();
    const waiting = document.querySelector('[data-reference="ENCASH_DIVISOR"]') as HTMLElement;
    await user.click(within(waiting).getByRole('button', { name: '✔' }));
    expect(assign).toHaveBeenCalledWith(expect.stringContaining('?s=W-01'));
  });

  it('SRS_LV_14_theSystemAdministratorOpensTheRegisterFromEachDrawnEntry', async () => {
    backend({
      'GET /v1/leave-pay-parameters': () => paged(versions),
      'GET /v1/masters/circulars/current': () => [],
    });
    const user = userEvent.setup();
    wrap(<LeaveParametersScreen />, me(['M-20:VIEW'], { roles: ['SYSTEM_ADMINISTRATOR'] }));
    await screen.findByText('CALENDAR_DAYS');
    const register = screen.getByTestId('m20-register');
    await user.click(screen.getByRole('button', { name: t('m20w.newVersion') }));
    await waitFor(() => expect(register).toHaveAttribute('open'));
    await user.click(within(register).getByText(t('m20w.register')));
    await waitFor(() => expect(register).not.toHaveAttribute('open'));
    await user.click(screen.getByRole('button', { name: t('mst.history') }));
    await waitFor(() => expect(register).toHaveAttribute('open'));
    await user.click(within(register).getByText(t('m20w.register')));
    await waitFor(() => expect(register).not.toHaveAttribute('open'));
    await user.click(screen.getByRole('button', { name: t('m20.supersede') }));
    await waitFor(() => expect(register).toHaveAttribute('open'));
    await user.click(within(register).getByText(t('m20w.register')));
    await waitFor(() => expect(register).not.toHaveAttribute('open'));
    // The Administrator's row edit opens the register but proposes nothing (no M-20 CREATE).
    const row = document.querySelector('[data-reference="LWP_DIVISOR"]') as HTMLElement;
    await user.click(within(row).getByRole('button', { name: new RegExp(t('common.edit')) }));
    await waitFor(() => expect(register).toHaveAttribute('open'));
  });
});

const READY = {
  id: 11,
  recordNumber: 'LEA-2026-0211',
  eis: '104882',
  name: 'Rakesh Sharma',
  separationDate: '2026-07-31',
  order: 'DEMO/DA-REV/LEA-01',
  revisionEffective: '2026-07-01',
  month: '202608',
  days: 300,
  oldBasic: 62400,
  oldDa: 31200,
  revisedBasic: 62400,
  revisedDa: 33072,
  paid: 936000,
  due: 954720,
  arrear: 18720,
  tds: 1872,
  status: 'CALCULATED',
  ineligibleReason: null,
  batch: null,
};

describe('I-24 remaining paths', () => {
  it('SRS_G_37_aCancellationDismissedSendsNothing', async () => {
    const calls = backend({
      'GET /v1/leave-encashment-arrears?': (url) => paged([READY], 1, url),
    });
    wrap(<LeaveEncashmentArrears />, me(['I-24:VIEW', 'I-24:SUBMIT']));
    const card = await screen.findByRole('table', { name: t('i24.invoices') });
    const row = (await within(card).findByText('LEA-2026-0211')).closest('tr') as HTMLElement;
    await userEvent.click(within(row).getByRole('button', { name: t('wf.inv.cancel') }));
    const dialog = await screen.findByRole('dialog', { name: t('i24.cancelTitle', ['LEA-2026-0211']) });
    await userEvent.click(within(dialog).getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(sent(calls, 'POST', '/cancellations')).toHaveLength(0);
  });

  it('SRS_G_30_theMakerStoresTheOrderOfAReadyRecordWithItsParticulars', async () => {
    const calls = backend({
      'GET /v1/reference/lists': () => REFERENCE,
      'GET /v1/leave-encashment-arrears?': (url) => paged([READY], 1, url),
      'GET /v1/document-types': () => [{ code: 'CIRCULAR', name: 'Circular', referenceRequired: true }],
      'GET /v1/leave-encashment-arrears/11/documents': () => [],
      'POST /v1/leave-encashment-arrears/11/documents': () => ({ status: 201, body: { id: 6 } }),
    });
    wrap(<LeaveEncashmentArrears />, me(['I-24:VIEW', 'I-24:SUBMIT']));
    await screen.findAllByText('LEA-2026-0211');
    const card = (await screen.findByText(t('wf.ord.title'))).closest('.card') as HTMLElement;
    await within(card).findByRole('option', { name: 'Other' });
    const [type] = Array.from(card.querySelectorAll('select')) as HTMLSelectElement[];
    const [reference, orderDate, office, files] = Array.from(
      card.querySelectorAll('input'),
    ) as HTMLInputElement[];
    const pdf = (name: string) => new File(['%PDF-1.7'], name, { type: 'application/pdf' });
    // A circular needs its reference; a type the master does not hold needs none.
    await userEvent.selectOptions(type as HTMLSelectElement, 'CIRCULAR');
    fireEvent.change(orderDate as HTMLInputElement, { target: { value: '2026-10-01' } });
    await userEvent.upload(files as HTMLInputElement, pdf('a.pdf'));
    expect(await within(card).findByText(t('wf.ord.particularsFirst'))).toBeInTheDocument();
    expect(sent(calls, 'POST', '/documents')).toHaveLength(0);
    await userEvent.selectOptions(type as HTMLSelectElement, 'OTHER');
    await userEvent.type(reference as HTMLInputElement, 'EP-01/2026');
    await userEvent.type(office as HTMLInputElement, 'RO Delhi');
    await userEvent.upload(files as HTMLInputElement, [pdf('a.pdf'), pdf('b.pdf')]);
    await waitFor(() =>
      expect(sent(calls, 'POST', '/v1/leave-encashment-arrears/11/documents')).toHaveLength(2),
    );
    const [first] = sent(calls, 'POST', '/v1/leave-encashment-arrears/11/documents') as FormData[];
    expect(first?.get('documentType')).toBe('OTHER');
    expect(first?.get('reference')).toBe('EP-01/2026');
    expect(first?.get('issuingOffice')).toBe('RO Delhi');
  });
});
