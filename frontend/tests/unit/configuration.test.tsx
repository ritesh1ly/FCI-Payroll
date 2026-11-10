import { cleanup, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  GlMapping,
  OtherParameters,
  SpecialRates,
  StaffRates,
  SystemParameters,
} from '@/modules/configuration';
import { backend, me, problem, referenceLists, sent, t, wrap, paged } from './support/harness';

/** SRS-20-5: the audit stores' retention classes as seeded (no period decided). */
const RETENTION = [
  {
    code: 'LEGACY_AUDIT',
    name: 'Legacy Audit table',
    months: null,
    indefinite: false,
    confirmed: false,
    basis: 'SRS-20-5',
    archivalAction: 'RETAIN',
    pendingApprovalId: null,
  },
  {
    code: 'NATIVE_AUDIT',
    name: 'Native audit trail',
    months: null,
    indefinite: false,
    confirmed: false,
    basis: 'SRS-20-5',
    archivalAction: 'RETAIN',
    pendingApprovalId: null,
  },
];

/**
 * The configuration screens (A-07, M-04 / M-22, M-23, M-24): what is in force, not seeded, pending,
 * the history, and a change as a new dated version under maker-checker with the OTP (SRS-G-1, SRS-G-6).
 */

afterEach(() => vi.unstubAllGlobals());

/** Maker-checker with an OTP: the first call answers 428, the second (with the OTP) succeeds. */
const governed = (answer: unknown) => (_url: string, init?: RequestInit) =>
  (JSON.parse(String(init?.body)) as { otp?: unknown }).otp ? answer : problem(428, 'AUTH-428-OTP', [7]);

async function answerOtp(code = '123456') {
  await userEvent.type(await screen.findByLabelText(t('otp.code')), code);
  await userEvent.click(screen.getByRole('button', { name: t('otp.verify') }));
}

async function cancelOtp() {
  await screen.findByLabelText(t('otp.code'));
  await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: t('common.cancel') }));
  await waitFor(() => expect(screen.queryByLabelText(t('otp.code'))).toBeNull());
}

const REFERENCE = referenceLists({
  PAY_PATTERN: [
    ['IDA', 'IDA'],
    ['CDA', 'CDA'],
  ],
  CITY_CLASS: [
    ['X', 'X'],
    ['Y', 'Y'],
  ],
  RATE_UNIT: [
    ['PERCENT', 'Percent'],
    ['INR', 'Rupees'],
  ],
});

describe('A-07 System Parameters', () => {
  const PARAMS = [
    {
      code: 'PASSWORD_MIN_LENGTH',
      group: 'SECURITY',
      valueType: 'INTEGER',
      value: 12,
      minValue: 8,
      maxValue: 64,
      unit: null,
      enforced: true,
      version: 2,
      effectiveFrom: '2026-04-01',
      changedBy: 'sysadmin@fci-demo.test',
      pending: null,
    },
    {
      code: 'SESSION_IDLE_TIMEOUT',
      group: 'SESSION',
      valueType: 'INTEGER',
      value: 15,
      minValue: 5,
      maxValue: 60,
      unit: 'MINUTES',
      enforced: false,
      version: 1,
      effectiveFrom: '2026-04-01',
      changedBy: 'system',
      pending: {
        value: 20,
        requestedBy: 'sysadmin@fci-demo.test',
        requestedAt: '2026-10-20T10:00:00Z',
        effectiveFrom: '2026-10-21',
        reason: 'Audit',
      },
    },
    {
      code: 'SINGLE_SESSION',
      group: 'SESSION',
      valueType: 'BOOLEAN',
      value: 1,
      minValue: 0,
      maxValue: 1,
      unit: null,
      enforced: true,
      version: 1,
      effectiveFrom: '2026-04-01',
      changedBy: 'system',
      pending: null,
    },
  ];

  it('ADM-SYS-001: groups the parameters, shows bounds, a pending change and the boolean as yes / no', async () => {
    backend({
      'GET /v1/admin/retention-classes': () => paged(RETENTION),
      'GET /v1/admin/parameters': () => paged(PARAMS),
    });
    wrap(<SystemParameters />, ['A-07:VIEW', 'A-07:EDIT']);
    expect(await screen.findByText(t('a07.group.SECURITY'))).toBeTruthy();
    expect(screen.getByText(t('a07.group.SESSION'))).toBeTruthy();
    expect(screen.queryByText(t('a07.group.PAYROLL'))).toBeNull();
    expect(screen.getByText('8 – 64')).toBeTruthy();
    expect(screen.getByText(new RegExp(t('a07.notEnforced')))).toBeTruthy();
    expect(screen.getByText(t('common.yes'))).toBeTruthy();
    // A parameter with a pending change cannot be changed again.
    expect(screen.getAllByRole('button', { name: t('action.editParameter') })).toHaveLength(2);
  });

  it('SRS-G-1: a change is a proposal with a reason and the OTP; the value in force stays', async () => {
    const calls = backend({
      'GET /v1/admin/retention-classes': () => paged(RETENTION),
      'GET /v1/admin/parameters': () => paged(PARAMS),
      'PUT /v1/admin/parameters/': governed({ status: 202, body: {} }),
    });
    wrap(<SystemParameters />, ['A-07:VIEW', 'A-07:EDIT']);
    await userEvent.click(
      (await screen.findAllByRole('button', { name: t('action.editParameter') }))[0] as HTMLElement,
    );
    const submit = screen.getByRole('button', { name: t('common.submitForApproval') }) as HTMLButtonElement;
    expect(submit.disabled).toBe(true);
    await userEvent.clear(screen.getByLabelText(t('a07.newValue')));
    await userEvent.type(screen.getByLabelText(t('a07.newValue')), '14');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Policy 2026');
    await userEvent.click(submit);
    await answerOtp();
    expect(await screen.findByText(t('a07.submitted'))).toBeTruthy();
    expect(sent(calls, 'PUT', '/PASSWORD_MIN_LENGTH').at(-1)).toEqual({
      value: 14,
      reason: 'Policy 2026',
      otp: { challengeId: 7, code: '123456' },
    });
  });

  it('a boolean is chosen from yes / no; a refusal is shown, a cancelled OTP is not, cancel closes', async () => {
    let answer: unknown = problem(422, 'COM-422-VALIDATION');
    backend({
      'GET /v1/admin/retention-classes': () => paged(RETENTION),
      'GET /v1/admin/parameters': () => paged(PARAMS),
      'PUT /v1/admin/parameters/': (url, init) =>
        answer === 'otp' ? problem(428, 'AUTH-428-OTP', [7]) : governed(answer)(url, init),
    });
    wrap(<SystemParameters />, ['A-07:VIEW', 'A-07:EDIT']);
    const edits = await screen.findAllByRole('button', { name: t('action.editParameter') });
    await userEvent.click(edits[1] as HTMLElement);
    await userEvent.selectOptions(screen.getByLabelText(t('a07.newValue')), '0');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Two devices');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    await answerOtp();
    expect(await screen.findByText(t('error.COM-422-VALIDATION'))).toBeTruthy();
    answer = 'otp';
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    await cancelOtp();
    await userEvent.click(screen.getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByLabelText(t('a07.newValue'))).toBeNull();
  });

  it('a viewer sees the values but cannot change them; a failed load is shown', async () => {
    backend({
      'GET /v1/admin/retention-classes': () => paged(RETENTION),
      'GET /v1/admin/parameters': () => paged(PARAMS),
    });
    wrap(<SystemParameters />, ['A-07:VIEW']);
    await screen.findByText(t('a07.group.SECURITY'));
    expect(screen.queryByRole('button', { name: t('action.editParameter') })).toBeNull();
  });

  it('a refused list is shown as the error', async () => {
    backend({
      'GET /v1/admin/retention-classes': () => paged(RETENTION),
      'GET /v1/admin/parameters': () => problem(403, 'AUTH-403-FORBIDDEN'),
    });
    wrap(<SystemParameters />, ['A-07:VIEW']);
    expect(await screen.findByText(t('error.AUTH-403-FORBIDDEN'))).toBeTruthy();
  });
});

describe('M-04 / M-22 rate masters', () => {
  const line = (over: object = {}) => ({
    designationId: null,
    categoryCode: null,
    payPattern: 'IDA',
    basicSlabFrom: null,
    basicSlabTo: null,
    cityClass: 'X',
    rateCode: 'HRA_X',
    rateValue: 30,
    unit: 'PERCENT',
    ...over,
  });
  const version = (over: object = {}) => ({
    id: 1,
    versionNo: 1,
    effectiveFrom: '2026-04-01',
    effectiveTo: null,
    state: 'APPROVED',
    approvalId: 1,
    circularOrderId: null,
    lines: [line()],
    ...over,
  });
  const MASTERS = [
    {
      code: 'HRA',
      name: 'House Rent Allowance',
      group: 'ALLOWANCE',
      payCode: 102,
      current: version({ circularOrderId: 5 }),
      pending: null,
    },
    {
      code: 'CCA',
      name: 'City Compensatory',
      group: 'ALLOWANCE',
      payCode: null,
      current: null,
      pending: null,
    },
    {
      code: 'DA',
      name: 'Dearness Allowance',
      group: 'DA',
      payCode: 103,
      current: version({
        effectiveTo: '2026-12-31',
        lines: [line({ rateCode: 'DA', basicSlabFrom: 0, basicSlabTo: 50000 })],
      }),
      pending: version({ id: 2, versionNo: 2, effectiveFrom: '2027-01-01', state: 'PENDING_APPROVAL' }),
    },
  ];
  function routes(over: Record<string, (url: string, init?: RequestInit) => unknown> = {}) {
    return backend({
      'GET /v1/reference/lists': () => REFERENCE,
      'GET /v1/masters/staff-rates': () => paged(MASTERS),
      // The drawn tables of M-04 have their own endpoints (master-screens.test.tsx covers them).
      'GET /v1/masters/staff-rates/dearness-versions': () => paged([]),
      'GET /v1/masters/staff-rates/city-classes': () => paged([]),
      'GET /v1/masters/staff-rates/HRA/versions': () =>
        paged([
          version({ id: 9, versionNo: 2, state: 'REJECTED' }),
          version({ effectiveTo: '2026-03-31' }),
          version({ id: 8, versionNo: 3, state: 'PENDING_APPROVAL' }),
        ]),
      'POST /v1/masters/staff-rates/': governed({ status: 201, body: version({ id: 3 }) }),
      'GET /v1/masters/circulars/current': () => [
        { id: 5, number: 'HQ/DA/5', subject: 'DA revision' },
        { id: 6, number: 'HQ/HRA/6', subject: 'HRA revision' },
      ],
      ...over,
    });
  }

  it('SRS-G-7: a rate administrator outside Headquarters sees New version disabled with the reason', async () => {
    routes();
    wrap(
      <StaffRates />,
      me(['M-04:VIEW', 'M-04:CREATE'], { atHeadquarters: false, hqOnlyScreens: ['M-04'] }),
    );
    await screen.findByText('City Compensatory');
    for (const button of screen.getAllByRole('button', { name: t('mst.newVersion') })) {
      expect(button).toBeDisabled();
      expect(button).toHaveAttribute('title', t('error.AUTHZ-403-HQ-ONLY'));
    }
  });

  // SC-16-0086 / SRS-1-62: a staff rate revision links its Active M-25 circular before submission.
  it('SRS-1-62: an M-04 revision needs its Active circular; the one in force is offered first', async () => {
    const calls = routes();
    wrap(<StaffRates />, ['M-04:VIEW', 'M-04:CREATE']);
    await screen.findByText('City Compensatory');
    await userEvent.click(screen.getAllByRole('button', { name: t('mst.newVersion') })[1] as HTMLElement);
    await userEvent.type(screen.getByLabelText(t('mst.rateCode')), 'CCA_A');
    await userEvent.type(screen.getByLabelText(t('mst.value')), '5');
    await userEvent.selectOptions(screen.getByLabelText(t('mst.pattern')), 'IDA');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'First');
    const submit = screen.getByRole('button', { name: t('common.submitForApproval') }) as HTMLButtonElement;
    expect(submit.disabled).toBe(true);
    expect(submit.getAttribute('title')).toBe(t('mst.why.circular'));
    await userEvent.selectOptions(await screen.findByLabelText(t('mst.circular')), '6');
    expect(submit.disabled).toBe(false);
    await userEvent.click(submit);
    await answerOtp();
    expect(sent(calls, 'POST', '/staff-rates/CCA/versions').at(-1)).toMatchObject({ circularOrderId: 6 });
  });

  it('SRS-1-62: without an Active circular the form says so', async () => {
    routes({ 'GET /v1/masters/circulars/current': () => [] });
    wrap(<StaffRates />, ['M-04:VIEW', 'M-04:CREATE']);
    await screen.findByText('City Compensatory');
    await userEvent.click(screen.getAllByRole('button', { name: t('mst.newVersion') })[1] as HTMLElement);
    expect(await screen.findByText(t('m27.noActiveCircular'))).toBeTruthy();
  });

  it('SRS-1-62: circulars that cannot be read leave none to link; a circular chosen and cleared is none', async () => {
    routes({ 'GET /v1/masters/circulars/current': () => problem(503, 'COM-503-UNAVAILABLE') });
    const first = wrap(<StaffRates />, ['M-04:VIEW', 'M-04:CREATE']);
    await screen.findByText('City Compensatory');
    await userEvent.click(screen.getAllByRole('button', { name: t('mst.newVersion') })[1] as HTMLElement);
    expect(await screen.findByText(t('m27.noActiveCircular'))).toBeTruthy();
    first.unmount();
    routes();
    wrap(<StaffRates />, ['M-04:VIEW', 'M-04:CREATE']);
    await screen.findByText('City Compensatory');
    await userEvent.click(screen.getAllByRole('button', { name: t('mst.newVersion') })[1] as HTMLElement);
    const circular = (await screen.findByLabelText(t('mst.circular'))) as HTMLSelectElement;
    await userEvent.selectOptions(circular, '6');
    await userEvent.selectOptions(circular, '');
    expect(circular.value).toBe('');
    expect(
      (screen.getByRole('button', { name: t('common.submitForApproval') }) as HTMLButtonElement).title,
    ).toBe(t('mst.why.circular'));
  });

  it('SRS-G-6: shows the version in force, a master not seeded, a pending version and the pay code', async () => {
    routes();
    wrap(<StaffRates />, ['M-04:VIEW', 'M-04:CREATE']);
    expect(await screen.findByText('House Rent Allowance')).toBeTruthy();
    expect(within(screen.getByTestId('m04-register')).getByText(t('mst.notSeeded'))).toBeTruthy();
    expect(screen.getByText(new RegExp(t('mst.notInFrs')))).toBeTruthy();
    expect(document.querySelector('.pill.p-warn')?.textContent).toMatch(/^v2 /);
    expect(screen.getByText(/0–50000/)).toBeTruthy();
    // DA has a pending version: no new version beside it.
    expect(screen.getAllByRole('button', { name: t('mst.newVersion') })).toHaveLength(2);
  });

  it('the history of a master opens and closes; its states are labelled', async () => {
    routes();
    wrap(<StaffRates />, ['M-04:VIEW']);
    await screen.findByText('House Rent Allowance');
    expect(screen.queryByRole('button', { name: t('mst.newVersion') })).toBeNull();
    const history = within(screen.getByTestId('m04-register')).getAllByRole('button', {
      name: t('mst.history'),
    })[0] as HTMLElement;
    await userEvent.click(history);
    expect(await screen.findByText(t('mst.historyOf', ['HRA']))).toBeTruthy();
    expect(screen.getByText(t('mst.state.REJECTED'))).toBeTruthy();
    expect(screen.getByText(t('mst.state.PENDING_APPROVAL'))).toBeTruthy();
    await userEvent.click(history);
    expect(screen.queryByText(t('mst.historyOf', ['HRA']))).toBeNull();
  });

  it('SRS-G-1: a new version is edited line by line and proposed with a reason and the OTP', async () => {
    const calls = routes();
    wrap(<StaffRates />, ['M-04:VIEW', 'M-04:CREATE']);
    await screen.findByText('House Rent Allowance');
    await userEvent.click(screen.getAllByRole('button', { name: t('mst.newVersion') })[0] as HTMLElement);
    expect(screen.getByText(t('mst.newVersionOf', ['House Rent Allowance']))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('mst.addLine') }));
    const rows = () => screen.getAllByLabelText(t('mst.rateCode'));
    expect(rows()).toHaveLength(2);
    const second = (label: string) => screen.getAllByLabelText(t(label))[1] as HTMLElement;
    await userEvent.selectOptions(second('mst.pattern'), 'CDA');
    await userEvent.selectOptions(second('mst.cityClass'), 'Y');
    await userEvent.type(second('mst.category'), 'A');
    await userEvent.type(second('mst.slabFrom'), '100');
    await userEvent.type(second('mst.slabTo'), '200');
    await userEvent.type(second('mst.rateCode'), 'hra_y');
    await userEvent.clear(second('mst.value'));
    await userEvent.type(second('mst.value'), '20');
    await userEvent.selectOptions(second('mst.unit'), 'PERCENT');
    const submit = screen.getByRole('button', { name: t('common.submitForApproval') }) as HTMLButtonElement;
    expect(submit.disabled).toBe(true);
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Circular 12/2026');
    await userEvent.clear(screen.getByLabelText(t('mst.from')));
    await userEvent.type(screen.getByLabelText(t('mst.from')), '2026-11-01');
    await userEvent.click(submit);
    await answerOtp();
    expect(await screen.findByText(t('mst.submitted', ['House Rent Allowance']))).toBeTruthy();
    const body = sent(calls, 'POST', '/staff-rates/HRA/versions').at(-1) as {
      effectiveFrom: string;
      lines: { rateCode: string; categoryCode: string; basicSlabTo: number; cityClass: string }[];
    };
    expect(body.effectiveFrom).toBe('2026-11-01');
    expect(body.lines[1]).toMatchObject({
      rateCode: 'HRA_Y',
      categoryCode: 'A',
      basicSlabTo: 200,
      cityClass: 'Y',
    });
  });

  it('an unseeded master starts with one empty line; removing it blocks the proposal; a refusal stays in the form', async () => {
    routes({ 'POST /v1/masters/staff-rates/': governed(problem(409, 'MST-409-OVERLAP')) });
    wrap(<StaffRates />, ['M-04:VIEW', 'M-04:CREATE']);
    await screen.findByText('City Compensatory');
    await userEvent.click(screen.getAllByRole('button', { name: t('mst.newVersion') })[1] as HTMLElement);
    expect(screen.getAllByLabelText(t('mst.rateCode'))).toHaveLength(1);
    await userEvent.type(screen.getByLabelText(t('mst.rateCode')), 'CCA_A');
    await userEvent.type(screen.getByLabelText(t('mst.value')), '5');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'First');
    // Clearing the slab inputs keeps them empty (null).
    await userEvent.type(screen.getByLabelText(t('mst.slabFrom')), '1');
    await userEvent.clear(screen.getByLabelText(t('mst.slabFrom')));
    await userEvent.type(screen.getByLabelText(t('mst.slabTo')), '1');
    await userEvent.clear(screen.getByLabelText(t('mst.slabTo')));
    await userEvent.selectOptions(screen.getByLabelText(t('mst.pattern')), '');
    await userEvent.selectOptions(screen.getByLabelText(t('mst.cityClass')), '');
    await userEvent.selectOptions(await screen.findByLabelText(t('mst.circular')), '5');
    // M-04: a line without its pay pattern is not submitted.
    expect(screen.getByRole('button', { name: t('common.submitForApproval') })).toBeDisabled();
    await userEvent.selectOptions(screen.getByLabelText(t('mst.pattern')), 'IDA');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    await answerOtp();
    expect(await screen.findByText(t('error.MST-409-OVERLAP'))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('mst.removeLine') }));
    expect(
      (screen.getByRole('button', { name: t('common.submitForApproval') }) as HTMLButtonElement).disabled,
    ).toBe(true);
    await userEvent.click(screen.getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByText(t('mst.newVersionOf', ['City Compensatory']))).toBeNull();
  });

  it('M-04 field table (SRS l.3651, l.3653): a cleared rate value and a missing pay pattern block the proposal with the reason', async () => {
    const calls = routes();
    wrap(<StaffRates />, ['M-04:VIEW', 'M-04:CREATE']);
    await screen.findByText('City Compensatory');
    await userEvent.click(screen.getAllByRole('button', { name: t('mst.newVersion') })[1] as HTMLElement);
    const value = screen.getByLabelText(t('mst.value')) as HTMLInputElement;
    // A new line starts blank, never at zero.
    expect(value.value).toBe('');
    await userEvent.type(screen.getByLabelText(t('mst.rateCode')), 'CCA_A');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'First');
    await userEvent.selectOptions(await screen.findByLabelText(t('mst.circular')), '6');
    const submit = screen.getByRole('button', { name: t('common.submitForApproval') }) as HTMLButtonElement;
    expect(submit).toBeDisabled();
    expect(submit).toHaveAttribute('title', t('mst.why.valueRequired'));
    expect(screen.getByTestId('mst-lines-why').textContent).toBe(t('mst.why.valueRequired'));
    expect(value).toHaveAttribute('aria-invalid', 'true');
    await userEvent.type(value, '0');
    // Zero entered is a value; the pay pattern is still missing.
    expect(submit).toHaveAttribute('title', t('mst.why.patternRequired'));
    await userEvent.clear(value);
    expect(value.value).toBe('');
    expect(submit).toHaveAttribute('title', t('mst.why.valueRequired'));
    await userEvent.type(value, '7.5');
    await userEvent.selectOptions(screen.getByLabelText(t('mst.pattern')), 'CDA');
    expect(screen.queryByTestId('mst-lines-why')).toBeNull();
    expect(submit).toBeEnabled();
    await userEvent.click(submit);
    await answerOtp();
    expect(sent(calls, 'POST', '/staff-rates/CCA/versions').at(-1)).toMatchObject({
      lines: [{ rateCode: 'CCA_A', rateValue: 7.5, payPattern: 'CDA' }],
    });
  });

  it('M-04 field table: an in-force line without a pay pattern must be given one before it is proposed again', async () => {
    routes({
      'GET /v1/masters/staff-rates': () =>
        paged([
          {
            code: 'HRA',
            name: 'House Rent Allowance',
            group: 'ALLOWANCE',
            payCode: 102,
            current: version({ circularOrderId: 6, lines: [line({ payPattern: null })] }),
            pending: null,
          },
        ]),
    });
    wrap(<StaffRates />, ['M-04:VIEW', 'M-04:CREATE']);
    await screen.findByText('House Rent Allowance');
    await userEvent.click(screen.getByRole('button', { name: t('mst.newVersion') }));
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Revision');
    const submit = screen.getByRole('button', { name: t('common.submitForApproval') }) as HTMLButtonElement;
    expect(submit).toHaveAttribute('title', t('mst.why.patternRequired'));
    expect(screen.getByLabelText(t('mst.pattern'))).toHaveAttribute('aria-invalid', 'true');
  });

  it('M-22: the pay pattern may stay blank (its field table is silent); a blank value still blocks', async () => {
    backend({
      'GET /v1/reference/lists': () => REFERENCE,
      'GET /v1/masters/special-rates': () =>
        paged([
          {
            code: 'SCA_NE',
            name: 'SCA North East',
            group: null,
            payCode: null,
            current: null,
            pending: null,
          },
        ]),
      'GET /v1/masters/circulars/current': () => [{ id: 6, number: 'CIR/SCA/1', subject: 'SCA revision' }],
    });
    wrap(<SpecialRates />, ['M-22:VIEW', 'M-22:CREATE']);
    await screen.findByText('SCA North East');
    await userEvent.click(screen.getByRole('button', { name: t('mst.newVersion') }));
    await userEvent.type(screen.getByLabelText(t('mst.rateCode')), 'SCA_NE');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'NE revision');
    await screen.findByRole('option', { name: 'CIR/SCA/1 · SCA revision' });
    await userEvent.selectOptions(screen.getByLabelText(t('mst.circular')), '6');
    const submit = screen.getByRole('button', { name: t('common.submitForApproval') }) as HTMLButtonElement;
    expect(submit).toHaveAttribute('title', t('mst.why.valueRequired'));
    await userEvent.type(screen.getByLabelText(t('mst.value')), '1500');
    expect(submit).toBeEnabled();
    expect(within(screen.getByTestId('m22-register')).getByLabelText(t('mst.pattern'))).not.toHaveAttribute(
      'aria-invalid',
      'true',
    );
  });

  it('a cancelled OTP leaves the form without an error', async () => {
    routes({ 'POST /v1/masters/staff-rates/': () => problem(428, 'AUTH-428-OTP', [7]) });
    wrap(<StaffRates />, ['M-04:VIEW', 'M-04:CREATE']);
    await screen.findByText('House Rent Allowance');
    await userEvent.click(screen.getAllByRole('button', { name: t('mst.newVersion') })[0] as HTMLElement);
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'x');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    await cancelOtp();
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.getByText(t('mst.newVersionOf', ['House Rent Allowance']))).toBeTruthy();
  });

  it('M-22 reads the special-rate masters', async () => {
    const calls = backend({
      'GET /v1/reference/lists': () => REFERENCE,
      'GET /v1/masters/special-rates': () => paged([]),
    });
    wrap(<SpecialRates />, ['M-22:VIEW']);
    expect(await screen.findByText(t('mst.hint.M-22'))).toBeTruthy();
    await waitFor(() => expect(calls.some((c) => c.url.includes('/special-rates'))).toBe(true));
  });

  it('SRS-11-15 (US-GAP-211): an M-22 version links its Active circular; a master without a group is listed; a cleared category is none', async () => {
    const calls = backend({
      'GET /v1/reference/lists': () => REFERENCE,
      'GET /v1/masters/special-rates': () =>
        paged([
          {
            code: 'SCA_NE',
            name: 'SCA North East',
            group: null,
            payCode: null,
            current: null,
            pending: null,
          },
        ]),
      'POST /v1/masters/special-rates/': governed({ status: 201, body: version({ id: 4 }) }),
      'GET /v1/masters/circulars/current': () => [{ id: 6, number: 'CIR/SCA/1', subject: 'SCA revision' }],
    });
    wrap(<SpecialRates />, ['M-22:VIEW', 'M-22:CREATE']);
    await screen.findByText('SCA North East');
    await userEvent.click(screen.getByRole('button', { name: t('mst.newVersion') }));
    await userEvent.type(screen.getByLabelText(t('mst.rateCode')), 'SCA_NE');
    await userEvent.type(screen.getByLabelText(t('mst.value')), '1500');
    await userEvent.type(screen.getByLabelText(t('mst.category')), 'A');
    await userEvent.clear(screen.getByLabelText(t('mst.category')));
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'NE revision');
    const submit = screen.getByRole('button', { name: t('common.submitForApproval') }) as HTMLButtonElement;
    // SRS l.2819 / SRS-G-38: a rate change is linked to its circular before it is submitted.
    expect(submit.disabled).toBe(true);
    expect(submit.getAttribute('title')).toBe(t('mst.why.circular'));
    await screen.findByRole('option', { name: 'CIR/SCA/1 · SCA revision' });
    await userEvent.selectOptions(screen.getByLabelText(t('mst.circular')), '6');
    await userEvent.click(submit);
    await answerOtp();
    const body = sent(calls, 'POST', '/special-rates/SCA_NE/versions').at(-1) as {
      circularOrderId: unknown;
      lines: { categoryCode: unknown }[];
    };
    expect(body.circularOrderId).toBe(6);
    expect(body.lines[0]).toMatchObject({ categoryCode: null });
  });
});

describe('M-23 Other parameters', () => {
  const v = (over: object = {}) => ({
    id: 1,
    versionNo: 1,
    effectiveFrom: '2026-04-01',
    effectiveTo: null,
    numericValue: null,
    ceilingValue: 7000,
    percentValue: 10,
    windowFromTime: '22:00',
    windowToTime: '06:00',
    instalmentCount: 3,
    payrollTypeScope: null,
    confirmed: true,
    status: 'APPROVED',
    approvalId: 1,
    ...over,
  });
  const ROWS = [
    { code: 'NIGHT_SHIFT', name: 'Night shift allowance', payCode: 220, current: v(), pending: null },
    { code: 'DEPUTATION', name: 'Deputation allowance', payCode: null, current: null, pending: null },
    {
      code: 'BONUS_CEILING',
      name: 'Bonus ceiling',
      payCode: null,
      current: v({
        confirmed: false,
        effectiveTo: '2026-12-31',
        ceilingValue: null,
        percentValue: null,
        windowFromTime: null,
        instalmentCount: null,
      }),
      pending: v({ id: 2, versionNo: 2, status: 'PENDING_APPROVAL', effectiveFrom: '2027-01-01' }),
    },
  ];
  function routes(post: (url: string, init?: RequestInit) => unknown = governed({ status: 201, body: v() })) {
    return backend({
      'GET /v1/masters/other-parameters': () => paged(ROWS),
      'GET /v1/masters/other-parameters/NIGHT_SHIFT/versions': () =>
        paged([v({ status: 'SUPERSEDED', effectiveTo: '2026-03-31', numericValue: 5 })]),
      'POST /v1/masters/other-parameters/': post,
    });
  }

  it('shows values, an unconfirmed and an unseeded parameter, a pending version and the history', async () => {
    routes();
    wrap(<OtherParameters />, ['M-23:VIEW', 'M-23:CREATE']);
    expect(await screen.findByText('Night shift allowance')).toBeTruthy();
    const register = within(screen.getByTestId('m23-register'));
    expect(register.getByText(/10% · ≤ ₹ 7,000 · 22:00–06:00 · × 3/)).toBeTruthy();
    expect(register.getByText(t('mst.unconfirmed'))).toBeTruthy();
    expect(register.getByText(t('mst.notSeeded'))).toBeTruthy();
    expect(screen.getAllByRole('button', { name: t('mst.newVersion') })).toHaveLength(2);
    const history = register.getAllByRole('button', { name: t('mst.history') })[0] as HTMLElement;
    await userEvent.click(history);
    expect(await screen.findByText(t('status.SUPERSEDED'))).toBeTruthy();
    await userEvent.click(history);
    expect(screen.queryByText(t('mst.historyOf', ['NIGHT_SHIFT']))).toBeNull();
  });

  it('SRS-G-1: a new version carries every field, a reason and the OTP', async () => {
    const calls = routes();
    wrap(<OtherParameters />, ['M-23:VIEW', 'M-23:CREATE']);
    await screen.findByText('Night shift allowance');
    await userEvent.click(screen.getAllByRole('button', { name: t('mst.newVersion') })[0] as HTMLElement);
    const field = (key: string) => screen.getByLabelText(t(key));
    await userEvent.clear(field('mst.percent'));
    await userEvent.type(field('mst.percent'), '12');
    await userEvent.type(field('mst.numeric'), '4');
    await userEvent.clear(field('mst.ceiling'));
    await userEvent.clear(field('mst.windowFrom'));
    await userEvent.type(field('mst.windowFrom'), '21:00');
    await userEvent.clear(field('mst.windowTo'));
    await userEvent.clear(field('mst.instalments'));
    await userEvent.type(field('mst.instalments'), '2');
    await userEvent.type(field('mst.appliesTo'), 'EMPLOYEE');
    await userEvent.click(field('mst.confirmed'));
    await userEvent.clear(field('mst.from'));
    await userEvent.type(field('mst.from'), '2026-11-01');
    await userEvent.type(field('common.reason'), 'Order 4/2026');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    await answerOtp();
    expect(await screen.findByText(t('mst.submitted', ['Night shift allowance']))).toBeTruthy();
    expect(sent(calls, 'POST', '/NIGHT_SHIFT/versions').at(-1)).toEqual({
      effectiveFrom: '2026-11-01',
      numericValue: 4,
      ceilingValue: null,
      percentValue: 12,
      windowFromTime: '21:00',
      windowToTime: null,
      instalmentCount: 2,
      payrollTypeScope: 'EMPLOYEE',
      confirmed: true,
      reason: 'Order 4/2026',
      otp: { challengeId: 7, code: '123456' },
    });
  });

  it('an unseeded parameter starts empty; a refusal stays, a cancelled OTP does not, cancel closes', async () => {
    let refuse = true;
    routes((url, init) =>
      refuse ? governed(problem(422, 'MST-422-CEILING'))(url, init) : problem(428, 'AUTH-428-OTP', [7]),
    );
    wrap(<OtherParameters />, ['M-23:VIEW', 'M-23:CREATE']);
    await screen.findByText('Deputation allowance');
    await userEvent.click(screen.getAllByRole('button', { name: t('mst.newVersion') })[1] as HTMLElement);
    expect((screen.getByLabelText(t('mst.percent')) as HTMLInputElement).value).toBe('');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'x');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    await answerOtp();
    expect(await screen.findByText(t('error.MST-422-CEILING'))).toBeTruthy();
    refuse = false;
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    await cancelOtp();
    await userEvent.click(screen.getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByLabelText(t('mst.percent'))).toBeNull();
  });
});

describe('M-24 GL mapping', () => {
  const m = (over: object = {}) => ({
    id: 1,
    versionNo: 1,
    effectiveFrom: '2026-04-01',
    effectiveTo: null,
    naturalAccount: '401-100',
    status: 'APPROVED',
    approvalId: 1,
    ...over,
  });
  const ROWS = [
    {
      payCode: 101,
      name: 'Basic Pay',
      type: 'EARNING',
      payslipGroup: 'PAY',
      retired: false,
      current: m(),
      pending: null,
    },
    {
      payCode: 150,
      name: 'Old allowance',
      type: 'EARNING',
      payslipGroup: null,
      retired: true,
      current: null,
      pending: null,
    },
    {
      payCode: 301,
      name: 'Income Tax',
      type: 'DEDUCTION',
      payslipGroup: null,
      retired: false,
      current: null,
      pending: m({ id: 2, naturalAccount: '501-200', status: 'PENDING_APPROVAL' }),
    },
    {
      payCode: 302,
      name: 'Professional Tax',
      type: 'DEDUCTION',
      payslipGroup: null,
      retired: false,
      current: null,
      pending: null,
    },
  ];
  function routes(post: (url: string, init?: RequestInit) => unknown = governed({ status: 201, body: m() })) {
    return backend({
      'GET /v1/masters/gl-mappings': () => paged(ROWS),
      'GET /v1/masters/gl-mappings/101/versions': () =>
        paged([m({ effectiveTo: '2026-03-31', status: 'SUPERSEDED' })]),
      'POST /v1/masters/gl-mappings/': post,
    });
  }

  it('flags unmapped pay codes, a retired one and a pending mapping; history opens and closes', async () => {
    routes();
    wrap(<GlMapping />, ['M-24:VIEW', 'M-24:CREATE']);
    expect(await screen.findByText('Basic Pay')).toBeTruthy();
    expect(screen.getAllByText(t('mst.notMapped'))).toHaveLength(3);
    expect(screen.getByText(new RegExp(t('mst.retired')))).toBeTruthy();
    expect(screen.getByText(/501-200/)).toBeTruthy();
    // Retired and pending pay codes cannot be remapped.
    expect(screen.getAllByRole('button', { name: t('mst.newVersion') })).toHaveLength(2);
    const history = within(screen.getByTestId('m24-register')).getAllByRole('button', {
      name: t('mst.history'),
    })[0] as HTMLElement;
    await userEvent.click(history);
    expect(await screen.findByText(t('status.SUPERSEDED'))).toBeTruthy();
    await userEvent.click(history);
    expect(screen.queryByText(t('mst.historyOf', [101]))).toBeNull();
  });

  it('SRS-G-7: Finance outside Headquarters sees New version disabled with the reason', async () => {
    routes();
    wrap(<GlMapping />, me(['M-24:VIEW', 'M-24:CREATE'], { atHeadquarters: false, hqOnlyScreens: ['M-24'] }));
    expect(await screen.findByText('Basic Pay')).toBeTruthy();
    const proposals = screen.getAllByRole('button', { name: t('mst.newVersion') });
    expect(proposals).toHaveLength(2);
    for (const button of proposals) {
      expect(button).toBeDisabled();
      expect(button).toHaveAttribute('title', t('error.AUTHZ-403-HQ-ONLY'));
    }
  });

  it('an empty catalogue says so', async () => {
    backend({ 'GET /v1/masters/gl-mappings': () => paged([]) });
    wrap(<GlMapping />, ['M-24:VIEW']);
    expect(await screen.findByText(t('mst.noPayCodes'))).toBeTruthy();
  });

  it('SRS-G-1: a mapping is proposed in upper case with a reason and the OTP; a malformed account cannot be sent', async () => {
    const calls = routes();
    wrap(<GlMapping />, ['M-24:VIEW', 'M-24:CREATE']);
    await screen.findByText('Basic Pay');
    await userEvent.click(screen.getAllByRole('button', { name: t('mst.newVersion') })[1] as HTMLElement);
    const account = screen.getByLabelText(t('mst.naturalAccount'));
    await userEvent.type(account, '-bad');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'New head');
    const submit = screen.getByRole('button', { name: t('common.submitForApproval') }) as HTMLButtonElement;
    expect(submit.disabled).toBe(true);
    await userEvent.clear(account);
    await userEvent.type(account, '502-300');
    await userEvent.clear(screen.getByLabelText(t('mst.from')));
    await userEvent.type(screen.getByLabelText(t('mst.from')), '2026-11-01');
    await userEvent.click(submit);
    await answerOtp();
    expect(await screen.findByText(t('mst.submitted', ['Professional Tax']))).toBeTruthy();
    expect(sent(calls, 'POST', '/gl-mappings/302/versions').at(-1)).toEqual({
      effectiveFrom: '2026-11-01',
      naturalAccount: '502-300',
      reason: 'New head',
      otp: { challengeId: 7, code: '123456' },
    });
  });

  it('a refusal stays in the form, a cancelled OTP does not; cancel closes', async () => {
    let refuse = true;
    routes((url, init) =>
      refuse ? governed(problem(409, 'MST-409-OVERLAP'))(url, init) : problem(428, 'AUTH-428-OTP', [7]),
    );
    wrap(<GlMapping />, ['M-24:VIEW', 'M-24:CREATE']);
    await screen.findByText('Basic Pay');
    await userEvent.click(screen.getAllByRole('button', { name: t('mst.newVersion') })[0] as HTMLElement);
    expect((screen.getByLabelText(t('mst.naturalAccount')) as HTMLInputElement).value).toBe('401-100');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'x');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    await answerOtp();
    expect(await screen.findByText(t('error.MST-409-OVERLAP'))).toBeTruthy();
    refuse = false;
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    await cancelOtp();
    await userEvent.click(screen.getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByLabelText(t('mst.naturalAccount'))).toBeNull();
  });
});

describe('SRS-20-5 audit retention periods on A-07', () => {
  const PARAMS_NONE: unknown[] = [];

  it('a store nobody has set shows "not decided"; a period is proposed with its order and takes effect on approval', async () => {
    const calls = backend({
      'GET /v1/admin/parameters': () => paged(PARAMS_NONE),
      'GET /v1/admin/retention-classes': () => paged(RETENTION),
      'PUT /v1/admin/retention-classes/': () => ({
        status: 202,
        body: { ...RETENTION[1], pendingApprovalId: 77 },
      }),
    });
    const user = userEvent.setup();
    wrap(<SystemParameters />, ['A-07:VIEW', 'A-07:EDIT']);
    expect((await screen.findAllByText(t('a07.retention.notDecided'))).length).toBe(2);
    const row = screen.getByText(t('a07.retention.class.NATIVE_AUDIT')).closest('tr') as HTMLElement;
    await user.click(within(row).getByRole('button', { name: t('a07.retention.propose') }));
    const submit = screen.getByRole('button', { name: t('common.submitForApproval') });
    // Months or indefinite, with the instrument and a reason: until then nothing can be sent.
    expect(submit).toHaveProperty('disabled', true);
    await user.type(screen.getByLabelText(t('a07.retention.monthsLabel')), '120');
    await user.type(screen.getByLabelText(t('a07.retention.basis')), 'FCI order 12/2026');
    expect(submit).toHaveProperty('disabled', true);
    await user.type(screen.getByLabelText(t('common.reason')), 'Records policy');
    expect(submit).toHaveProperty('disabled', false);
    // Indefinite clears and disables the months.
    await user.click(screen.getByLabelText(t('a07.retention.indefiniteLabel')));
    expect(screen.getByLabelText(t('a07.retention.monthsLabel'))).toHaveProperty('disabled', true);
    await user.click(screen.getByLabelText(t('a07.retention.indefiniteLabel')));
    await user.type(screen.getByLabelText(t('a07.retention.monthsLabel')), '120');
    await user.click(submit);
    await waitFor(() =>
      expect(sent(calls, 'PUT', '/v1/admin/retention-classes/NATIVE_AUDIT')).toEqual([
        { months: 120, indefinite: false, basis: 'FCI order 12/2026', reason: 'Records policy' },
      ]),
    );
    expect(await screen.findByText(t('a07.retention.proposed'))).toBeTruthy();
  });

  it('a set period, an indefinite one and a pending change are shown; a viewer cannot propose; a refusal is shown', async () => {
    backend({
      'GET /v1/admin/parameters': () => paged(PARAMS_NONE),
      'GET /v1/admin/retention-classes': () =>
        paged([
          { ...RETENTION[0], indefinite: true, confirmed: true, basis: 'Order A' },
          { ...RETENTION[1], months: 120, confirmed: true, basis: 'Order B', pendingApprovalId: 5 },
        ]),
    });
    wrap(<SystemParameters />, ['A-07:VIEW']);
    expect(await screen.findByText(t('a07.retention.indefinite'))).toBeTruthy();
    expect(screen.getByText(t('a07.retention.months', [120]))).toBeTruthy();
    expect(screen.getByText(t('a07.retention.pending', [5]))).toBeTruthy();
    expect(screen.queryByRole('button', { name: t('a07.retention.propose') })).toBeNull();
    cleanup();
    vi.unstubAllGlobals();

    backend({
      'GET /v1/admin/parameters': () => paged(PARAMS_NONE),
      'GET /v1/admin/retention-classes': () => paged(RETENTION),
      'PUT /v1/admin/retention-classes/': () => problem(422, 'AUD-422-RETENTION', [99999]),
    });
    const user = userEvent.setup();
    wrap(<SystemParameters />, ['A-07:VIEW', 'A-07:EDIT']);
    const row = (await screen.findByText(t('a07.retention.class.LEGACY_AUDIT'))).closest('tr') as HTMLElement;
    await user.click(within(row).getByRole('button', { name: t('a07.retention.propose') }));
    await user.click(screen.getByLabelText(t('a07.retention.indefiniteLabel')));
    await user.type(screen.getByLabelText(t('a07.retention.basis')), 'Order C');
    await user.type(screen.getByLabelText(t('common.reason')), 'Policy');
    await user.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    expect(await screen.findByText(t('error.AUD-422-RETENTION', [99999]))).toBeTruthy();
    await user.click(screen.getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByLabelText(t('a07.retention.basis'))).toBeNull();
  });

  it('SRS-20-29: every setting opens its change history', async () => {
    const one = {
      code: 'PASSWORD_MIN_LENGTH',
      group: 'SECURITY',
      valueType: 'INTEGER',
      value: 12,
      minValue: 8,
      maxValue: 64,
      unit: null,
      enforced: true,
      version: 1,
      effectiveFrom: '2026-04-01',
      changedBy: 'system',
      pending: null,
    };
    backend({
      'GET /v1/admin/parameters': () => paged([one]),
      'GET /v1/admin/retention-classes': () => paged(RETENTION),
    });
    wrap(<SystemParameters />, ['A-07:VIEW']);
    expect(
      await screen.findByRole('button', { name: t('history.for', [t('a07.param.PASSWORD_MIN_LENGTH')]) }),
    ).toBeTruthy();
  });
});
