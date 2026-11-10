import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { LeaveEvents, LeavePayParameters, LeaveTypes } from '@/modules/leave';
import { backend, me, paged, problem, sent, t, wrap } from './support/harness';

/**
 * Module 19 screens (plan 19 phases 19a / 19b): M-13 leave types with their pay effect, I-11 leave
 * events with their status and the X-05 hold count, M-20 parameter versions and the Administrator's
 * new version under maker-checker.
 */

afterEach(() => vi.unstubAllGlobals());

const TYPES = [
  {
    code: 'EL',
    name: 'Earned Leave',
    encashable: true,
    halfDay: false,
    payEffect: 'NO_EFFECT',
    lastReceived: '2026-10-01T10:00:00',
  },
  {
    code: 'LWP',
    name: 'Leave without pay',
    encashable: false,
    halfDay: true,
    payEffect: null,
    lastReceived: null,
  },
];

const EVENTS = [
  {
    applicationId: 2,
    sanctionRef: 'SAN/2026/7',
    eis: '104882',
    name: 'Rakesh Sharma',
    office: 'DH001 - Delhi Division',
    leaveType: 'LWP',
    leaveTypeName: 'Leave without pay',
    from: '2026-10-05',
    to: '2026-10-06',
    days: 2,
    eventType: 'ADDITION',
    received: '2026-10-07T09:00:00',
    payEffect: 'FULL_DEDUCTION',
    status: 'APPLIED',
    appliedIn: '2026-10',
    rule: 'BR-LEAVE-007',
  },
  {
    applicationId: 1,
    sanctionRef: 'SAN/2026/3',
    eis: '104882',
    name: 'Rakesh Sharma',
    office: null,
    leaveType: 'EL',
    leaveTypeName: null,
    from: '2026-08-01',
    to: '2026-08-01',
    days: 1,
    eventType: 'CANCELLATION',
    received: null,
    payEffect: null,
    status: 'PENDING',
    appliedIn: null,
    rule: null,
  },
];

describe('M-13 leave types', () => {
  it('SRS-LV-24: the types from HRMS with their pay effect; a type without one is marked, nothing is edited', async () => {
    const calls = backend({
      'GET /v1/leave-types/counts': () => ({ received: 2, encashable: 1, payReducing: 0, lastSync: null }),
      'GET /v1/leave-types?': (url) => paged(TYPES, 2, url),
    });
    wrap(<LeaveTypes />, me(['M-13:VIEW']));
    const table = await screen.findByRole('table', { name: t('screen.M-13') });
    expect(within(table).getByText(t('lv.effect.NO_EFFECT'))).toBeTruthy();
    expect(within(table).getByText(t('m13.noPayEffect'))).toBeTruthy();
    expect(within(table).getByText(t('m13.halfDay'))).toBeTruthy();
    // SRS-20-29 (US-03-07 AC1): each leave type opens its change history.
    expect(within(table).getByRole('button', { name: t('history.for', ['EL · Earned Leave']) })).toBeTruthy();
    expect(
      within(table).getByRole('button', { name: t('history.for', ['LWP · Leave without pay']) }),
    ).toBeTruthy();
    expect(await screen.findByText(t('m13.tile.encashable'))).toBeTruthy();
    expect(calls.some((c) => c.method !== 'GET')).toBe(false);
    // M-13 Export only with the EXPORT grant.
    expect(screen.queryByRole('button', { name: t('action.export') })).toBeNull();
  });

  it('SRS-G-13: M-13 is exported with the EXPORT grant', async () => {
    backend({
      'GET /v1/leave-types/counts': () => ({ received: 2, encashable: 1, payReducing: 0, lastSync: null }),
      'GET /v1/leave-types?': (url) => paged(TYPES, 2, url),
    });
    wrap(<LeaveTypes />, me(['M-13:VIEW', 'M-13:EXPORT']));
    await screen.findByRole('table', { name: t('screen.M-13') });
    expect(screen.getByRole('button', { name: t('action.export') })).toBeTruthy();
  });
});

describe('I-11 leave events', () => {
  it('SRS-LV-26 / SRS-LV-13: the events with status and pay effect, the held count, the filters', async () => {
    const calls = backend({
      'GET /v1/leave-events/counts': () => ({ received: 2, applied: 1, backDated: 0, held: 3 }),
      'GET /v1/leave-events?': (url) => paged(EVENTS, 2, url),
    });
    wrap(<LeaveEvents />, me(['I-11:VIEW']));
    const table = await screen.findByRole('table', { name: t('screen.I-11') });
    expect(within(table).getByText(t('i11.status.APPLIED'))).toBeTruthy();
    expect(within(table).getByText(t('lv.effect.FULL_DEDUCTION'))).toBeTruthy();
    // SRS I-11 (US-GAP-59): the rule the pay effect applies and the cycle it applies in.
    expect(within(table).getByText('BR-LEAVE-007')).toBeTruthy();
    expect(within(table).getByText('10-2026')).toBeTruthy();
    expect(within(table).getByText('LWP - Leave without pay')).toBeTruthy();
    expect(within(table).getByText(t('i11.cancellation'))).toBeTruthy();
    expect(within(table).getByText('DH001 - Delhi Division')).toBeTruthy();
    expect(await screen.findByText('3')).toBeTruthy();
    await userEvent.selectOptions(screen.getByLabelText(t('i11.status')), 'BACK_DATED');
    await userEvent.type(screen.getByLabelText(t('m01.eis')), '104882');
    await userEvent.type(screen.getByLabelText(t('i11.leaveType')), 'el');
    expect(
      calls.some(
        (c) =>
          c.url.includes('status=BACK_DATED') &&
          c.url.includes('eis=104882') &&
          c.url.includes('leaveType=EL'),
      ),
    ).toBe(true);
    expect(calls.some((c) => c.method !== 'GET')).toBe(false);
    expect(screen.queryByRole('button', { name: t('action.export') })).toBeNull();
  });

  it('SRS-G-13: I-11 is exported with the filters on screen, with the EXPORT grant only', async () => {
    backend({
      'GET /v1/leave-events/counts': () => ({ received: 2, applied: 1, backDated: 0, held: 0 }),
      'GET /v1/leave-events?': (url) => paged(EVENTS, 2, url),
    });
    wrap(<LeaveEvents />, me(['I-11:VIEW', 'I-11:EXPORT']));
    await screen.findByRole('table', { name: t('screen.I-11') });
    expect(screen.getByRole('button', { name: t('action.export') })).toBeTruthy();
    const { leaveApi } = await import('@/modules/leave/api/leaveApi');
    expect(leaveApi.eventsExportUrl({})).toBe('/nginx/fci-payroll/v1/leave-events/export');
    expect(leaveApi.eventsExportUrl({ status: 'APPLIED', eis: '104882', leaveType: 'EL' })).toBe(
      '/nginx/fci-payroll/v1/leave-events/export?status=APPLIED&eis=104882&leaveType=EL',
    );
    expect(leaveApi.typesExportUrl()).toBe('/nginx/fci-payroll/v1/leave-types/export');
  });

  it('SRS-LV-13: with nothing held the held tile is not flagged', async () => {
    backend({
      'GET /v1/leave-events/counts': () => ({ received: 2, applied: 2, backDated: 0, held: 0 }),
      'GET /v1/leave-events?': (url) => paged([], 0, url),
    });
    const { container } = wrap(<LeaveEvents />, me(['I-11:VIEW']));
    await screen.findByText(t('i11.tile.held'));
    expect(container.querySelector('.fl-bad')).toBeNull();
    expect(container.querySelector('.fl-tico-info')).not.toBeNull();
  });
});

describe('M-20 leave pay parameters', () => {
  const VERSIONS = [
    {
      id: 3,
      code: 'PAY_EFFECT',
      leaveType: 'LWP',
      number: null,
      text: 'FULL_DEDUCTION',
      versionNo: 1,
      effectiveFrom: '2026-10-01',
      effectiveTo: null,
      status: 'ACTIVE',
      approvalId: 9,
      circularNumber: 'HQ/LV/2026/1',
    },
    {
      id: 2,
      code: 'ENCASH_DIVISOR',
      leaveType: null,
      number: 30,
      text: null,
      versionNo: 2,
      effectiveFrom: '2026-04-01',
      effectiveTo: '2026-09-30',
      status: 'SUPERSEDED',
      approvalId: 7,
      circularNumber: null,
    },
  ];

  it('SRS-LV-14: the versions with their status; the Administrator proposes one with a reason', async () => {
    const calls = backend({
      'GET /v1/leave-pay-parameters?': (url) => paged(VERSIONS, 2, url),
      'GET /v1/masters/circulars/current': () => [{ id: 12, number: 'HQ/LV/2026/2', subject: 'Leave' }],
      'POST /v1/leave-pay-parameters': () => ({
        status: 202,
        body: { ...VERSIONS[1], id: 4, status: 'PENDING_APPROVAL' },
      }),
    });
    wrap(<LeavePayParameters />, me(['M-20:VIEW', 'M-20:CREATE']));
    const table = await screen.findByRole('table', { name: t('screen.M-20') });
    // SRS M-20: the linked circular is shown on its version.
    expect(within(table).getByText('HQ/LV/2026/1')).toBeTruthy();
    expect(within(table).getByText(t('m20.status.SUPERSEDED'))).toBeTruthy();
    expect(within(table).getByText(t('lv.effect.FULL_DEDUCTION'))).toBeTruthy();
    expect(within(table).getByText('30')).toBeTruthy();
    // SRS-20-29 (US-03-07 AC1): each version opens the history of its parameter and leave type.
    expect(
      within(table).getByRole('button', { name: t('history.for', [`${t('m20.param.PAY_EFFECT')} / LWP`]) }),
    ).toBeTruthy();
    expect(
      within(table).getByRole('button', { name: t('history.for', [t('m20.param.ENCASH_DIVISOR')]) }),
    ).toBeTruthy();

    await userEvent.click(screen.getByRole('button', { name: t('m20.newVersion') }));
    const submit = screen.getByRole('button', { name: t('common.submitForApproval') });
    expect(submit).toBeDisabled();
    await userEvent.selectOptions(screen.getByLabelText(t('m20.parameter')), 'LWP_DIVISOR');
    await userEvent.type(screen.getByLabelText(t('m20.value')), 'CALENDAR_DAYS');
    await userEvent.type(screen.getByLabelText(t('m20.effectiveFrom')), '2026-11-01');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'As in the current system');
    expect(submit).not.toBeDisabled();
    await userEvent.click(submit);
    expect(await screen.findByText(t('m20.submitted', ['LWP_DIVISOR']))).toBeTruthy();
    expect(sent(calls, 'POST', '/v1/leave-pay-parameters').at(-1)).toEqual({
      code: 'LWP_DIVISOR',
      leaveType: null,
      number: null,
      text: 'CALENDAR_DAYS',
      effectiveFrom: '2026-11-01',
      reason: 'As in the current system',
      circularId: null,
    });
  });

  it('SRS-LV-14: Supersede on an active row opens the form for its parameter and leave type, with the circular where an order applies', async () => {
    const calls = backend({
      'GET /v1/leave-pay-parameters?': (url) => paged(VERSIONS, 2, url),
      'GET /v1/masters/circulars/current': () => [{ id: 12, number: 'HQ/LV/2026/2', subject: 'Leave' }],
      'POST /v1/leave-pay-parameters': () => ({
        status: 202,
        body: { ...VERSIONS[0], id: 5, status: 'PENDING_APPROVAL' },
      }),
    });
    wrap(<LeavePayParameters />, me(['M-20:VIEW', 'M-20:CREATE']));
    const table = await screen.findByRole('table', { name: t('screen.M-20') });
    const supersede = within(table).getAllByRole('button', { name: t('m20.supersede') });
    expect(supersede).toHaveLength(1);
    await userEvent.click(supersede[0] as HTMLElement);
    expect((screen.getByLabelText(t('m20.parameter')) as HTMLSelectElement).value).toBe('PAY_EFFECT');
    expect((screen.getByLabelText(t('m20.leaveType')) as HTMLInputElement).value).toBe('LWP');
    await userEvent.selectOptions(screen.getByLabelText(t('m20.value')), 'HALF_PAY');
    await userEvent.type(screen.getByLabelText(t('m20.effectiveFrom')), '2026-12-01');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Order 12');
    await userEvent.selectOptions(
      screen.getByLabelText(t('m20.circular')),
      await screen.findByRole('option', { name: 'HQ/LV/2026/2 - Leave' }),
    );
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    expect(await screen.findByText(t('m20.submitted', ['PAY_EFFECT']))).toBeTruthy();
    expect(sent(calls, 'POST', '/v1/leave-pay-parameters').at(-1)).toMatchObject({
      code: 'PAY_EFFECT',
      leaveType: 'LWP',
      text: 'HALF_PAY',
      circularId: 12,
    });
  });

  it('SRS-LV-14: without CREATE Supersede is disabled with the reason', async () => {
    backend({ 'GET /v1/leave-pay-parameters?': (url) => paged(VERSIONS, 2, url) });
    wrap(<LeavePayParameters />, me(['M-20:VIEW']));
    const table = await screen.findByRole('table', { name: t('screen.M-20') });
    const supersede = within(table).getByRole('button', { name: t('m20.supersede') });
    expect(supersede).toBeDisabled();
    expect(supersede).toHaveAttribute('title', t('reason.ruleAdminOnly'));
  });

  it('SRS-LV-14: a numeric divisor is sent as a number; a version without a value shows a dash', async () => {
    const calls = backend({
      'GET /v1/masters/circulars/current': () => [],
      'GET /v1/leave-pay-parameters?': (url) =>
        paged([{ ...VERSIONS[1], id: 5, code: 'LWP_COMPONENTS', number: null, text: null }], 1, url),
      'POST /v1/leave-pay-parameters': () => ({
        status: 202,
        body: { ...VERSIONS[1], id: 6, status: 'PENDING_APPROVAL' },
      }),
    });
    wrap(<LeavePayParameters />, me(['M-20:VIEW', 'M-20:CREATE']));
    const table = await screen.findByRole('table', { name: t('screen.M-20') });
    expect(within(table).getAllByText('—').length).toBeGreaterThan(0);
    await userEvent.click(screen.getByRole('button', { name: t('m20.newVersion') }));
    await userEvent.selectOptions(screen.getByLabelText(t('m20.parameter')), 'ENCASH_DIVISOR');
    await userEvent.type(screen.getByLabelText(t('m20.value')), '30');
    await userEvent.type(screen.getByLabelText(t('m20.effectiveFrom')), '2026-11-01');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Thirty days');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    expect(await screen.findByText(t('m20.submitted', ['ENCASH_DIVISOR']))).toBeTruthy();
    expect(sent(calls, 'POST', '/v1/leave-pay-parameters').at(-1)).toMatchObject({
      code: 'ENCASH_DIVISOR',
      number: 30,
      text: null,
    });
  });

  it('SRS-LV-27: a pay effect is chosen per leave type; a refusal is shown; without CREATE the action is disabled', async () => {
    const calls = backend({
      'GET /v1/masters/circulars/current': () => [],
      'GET /v1/leave-pay-parameters?': (url) => paged([], 0, url),
      'POST /v1/leave-pay-parameters': () =>
        problem(409, 'LV-409-PARAMETER-DATE', ['PAY_EFFECT', '2026-10-01']),
    });
    const view = wrap(<LeavePayParameters />, me(['M-20:VIEW', 'M-20:CREATE']));
    expect(await screen.findByText(t('m20.none'))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('m20.newVersion') }));
    await userEvent.selectOptions(screen.getByLabelText(t('m20.parameter')), 'PAY_EFFECT');
    await userEvent.selectOptions(screen.getByLabelText(t('m20.value')), 'HALF_PAY');
    await userEvent.type(screen.getByLabelText(t('m20.effectiveFrom')), '2026-09-01');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'HPL as now');
    const submit = screen.getByRole('button', { name: t('common.submitForApproval') });
    expect(submit).toBeDisabled();
    expect(submit.getAttribute('title')).toBe(t('m20.problem.leaveType'));
    await userEvent.type(screen.getByLabelText(t('m20.leaveType')), 'hpl');
    await userEvent.click(submit);
    expect(
      await screen.findByText(t('error.LV-409-PARAMETER-DATE', ['PAY_EFFECT', '2026-10-01'])),
    ).toBeTruthy();
    expect(sent(calls, 'POST', '/v1/leave-pay-parameters').at(-1)).toMatchObject({
      code: 'PAY_EFFECT',
      leaveType: 'HPL',
      number: null,
      text: 'HALF_PAY',
    });
    await userEvent.click(screen.getByRole('button', { name: t('common.cancel') }));
    view.unmount();

    backend({ 'GET /v1/leave-pay-parameters?': (url) => paged([], 0, url) });
    wrap(<LeavePayParameters />, me(['M-20:VIEW']));
    expect(await screen.findByRole('button', { name: t('m20.newVersion') })).toBeDisabled();
  });
});
