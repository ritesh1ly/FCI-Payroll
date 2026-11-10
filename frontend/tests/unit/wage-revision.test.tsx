import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { WageRevisions } from '@/modules/arrear';
import { wageRevisionApi } from '@/modules/arrear/api/wageRevisionApi';
import {
  definitionReady,
  isFixed,
  isOtpApproved,
  maySimulate,
  maySubmit,
  officeState,
  revisionOrders,
  revisionTone,
} from '@/modules/arrear/business/wageRevisionRules';
import { backend, me, paged, problem, referenceLists, sent, t, wrap } from './support/harness';

/**
 * I-25 Wage Revision steps 1 to 4 (plan 13 phase 13e): Headquarters defines, simulates and submits
 * a revision; the nine-step panel; each action disabled with its reason.
 */

afterEach(() => vi.unstubAllGlobals());

const SUMMARY = {
  id: 4,
  code: 'WR-2026-0004',
  name: 'Pay revision',
  order: 'HQ/WR/2026',
  payrollTypes: 'STAFF_IDA,STAFF_CDA',
  status: 'DEFINED',
  step: 2,
  createdAt: '2026-10-20T10:00:00',
  scope: 'ALL_INDIA',
  effectiveFrom: '2026-08-01',
  rule: 'PERCENTAGE',
  ruleValue: 3,
  rounding: 'NEXT_10',
};

/** OD-123: the form's dropdowns read the M-16 lists. */
const LISTS = {
  'GET /v1/reference/lists': () =>
    referenceLists({
      WAGE_REVISION_FITMENT: [
        ['FACTOR', 'Factor'],
        ['PERCENTAGE', 'Percentage'],
        ['FIXED_AMOUNT', 'Fixed amount'],
      ],
      WAGE_REVISION_ROUNDING: [
        ['RUPEE', 'To the rupee'],
        ['NEXT_10', 'Up to the next 10'],
        ['NEXT_100', 'Up to the next 100'],
      ],
    }),
};

const openDefine = async () =>
  userEvent.click(await screen.findByRole('button', { name: t('i25.act.define') }));
const openRow = async (table: HTMLElement) => userEvent.click(await within(table).findByText(SUMMARY.code));

const steps = (done: number) =>
  ['ORDER', 'DEFINE', 'SIMULATE', 'APPROVE', 'FIX_PAY', 'ARREARS', 'PAY', 'TRACK', 'CORRECT'].map(
    (code, i) => ({
      no: i + 1,
      code,
      status: i < done ? 'DONE' : i === 8 ? 'SKIPPED' : 'PENDING',
      by: i < done ? 'su.hq@fci-demo.test' : null,
      at: null,
    }),
  );

const detail = (over: object = {}) => ({
  summary: SUMMARY,
  effectiveFrom: '2026-08-01',
  rule: 'PERCENTAGE',
  value: 3,
  minimum: 18000,
  maximum: null,
  rounding: 'NEXT_10',
  instalments: 1,
  steps: steps(2),
  members: 0,
  estimatedArrear: 0,
  ...over,
});

const MEMBER = {
  eis: '104882',
  name: 'Rakesh Sharma',
  office: 'DH001 - Delhi Division',
  oldPay: 62400,
  newPay: 64280,
  estimate: 3760,
  status: 'PENDING',
};

describe('I-25 Wage Revision', () => {
  it('SRS-8-25: the rules decide what the screen offers', () => {
    const ready = {
      name: 'Pay revision',
      circularId: 9,
      payrollTypes: ['STAFF_IDA'],
      effectiveFrom: '2026-08-01',
      fitment: 'PERCENTAGE' as const,
      value: 3,
      rounding: 'RUPEE' as const,
      reason: 'Order',
    };
    expect(definitionReady(ready)).toBe(true);
    expect(definitionReady({ ...ready, payrollTypes: [] })).toBe(false);
    expect(definitionReady({ ...ready, value: 0 })).toBe(false);
    expect(definitionReady({ ...ready, minimum: 5, maximum: 4 })).toBe(false);
    expect(definitionReady({ ...ready, reason: ' ' })).toBe(false);
    expect(maySimulate(detail() as never)).toBe(true);
    expect(maySimulate(detail({ summary: { ...SUMMARY, status: 'APPROVED' } }) as never)).toBe(false);
    expect(maySubmit(detail({ summary: { ...SUMMARY, status: 'SIMULATED' } }) as never)).toBe(true);
    expect(maySubmit(detail() as never)).toBe(false);
    expect(wageRevisionApi.membersUrl(4)).toMatch(/\/v1\/wage-revisions\/4\/members\/export$/);
  });

  it('SRS-8-25 steps 1 - 2: Headquarters defines a revision on an Active order', async () => {
    const calls = backend({
      ...LISTS,
      'GET /v1/masters/circulars/current': () => [
        { id: 9, number: 'HQ/WR/2026', subject: 'Pay revision', category: 'WAGE_REVISION' },
      ],
      'GET /v1/wage-revisions?': (url) => paged([SUMMARY], 1, url),
      'POST /v1/wage-revisions': () => ({ status: 201, body: detail() }),
      'GET /v1/wage-revisions/4': () => detail(),
      'GET /v1/wage-revisions/4/members?': (url) => paged([], 0, url),
    });
    wrap(<WageRevisions />, me(['I-25:VIEW', 'I-25:CREATE']));
    await openDefine();
    const define = await screen.findByRole('button', { name: t('action.i25.define') });
    expect(define).toBeDisabled();
    expect(define.getAttribute('title')).toBe(t('i25.why.define'));
    await userEvent.type(screen.getByLabelText(t('i25.name')), 'Pay revision');
    await userEvent.selectOptions(
      screen.getAllByLabelText(t('i25.order'))[0] as HTMLElement,
      await screen.findByRole('option', { name: /HQ\/WR\/2026/ }),
    );
    await userEvent.click(screen.getByLabelText(t('i25.type.STAFF_IDA')));
    await userEvent.type(screen.getByLabelText(t('i25.effective')), '2026-08-01');
    await userEvent.selectOptions(screen.getByLabelText(t('i25.fitment')), 'PERCENTAGE');
    await userEvent.type(screen.getByLabelText(t('i25.value')), '3');
    await userEvent.type(screen.getByLabelText(t('i25.minimum')), '18000');
    await userEvent.selectOptions(screen.getByLabelText(t('i25.rounding')), 'NEXT_10');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Order');
    await userEvent.click(define);
    expect(await screen.findByText(t('i25.defined'))).toBeTruthy();
    expect(sent(calls, 'POST', '/v1/wage-revisions').at(-1)).toEqual({
      name: 'Pay revision',
      circularId: 9,
      payrollTypes: ['STAFF_IDA'],
      effectiveFrom: '2026-08-01',
      fitment: 'PERCENTAGE',
      value: 3,
      minimum: 18000,
      rounding: 'NEXT_10',
      reason: 'Order',
    });
    expect(await screen.findByRole('list', { name: t('i25.steps') })).toBeTruthy();
    // Review 13-20: the form closes, so a second click defines no duplicate; opened again it is empty.
    expect(screen.queryByRole('dialog')).toBeNull();
    await openDefine();
    expect(screen.getByLabelText(t('i25.name'))).toHaveValue('');
    expect(screen.getByLabelText(t('i25.value'))).toHaveValue('');
    expect(screen.getByLabelText(t('i25.minimum'))).toHaveValue('');
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.close') }),
    );
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('Review X3: a value or bound that is not a number blocks Define (never sent as null)', () => {
    const ready = {
      name: 'Pay revision',
      circularId: 9,
      payrollTypes: ['STAFF_IDA'],
      effectiveFrom: '2026-08-01',
      fitment: 'PERCENTAGE',
      value: 3,
      rounding: 'RUPEE',
      reason: 'Order',
    } as never;
    expect(definitionReady(ready)).toBe(true);
    expect(definitionReady({ ...(ready as object), value: Number.NaN } as never)).toBe(false);
    expect(definitionReady({ ...(ready as object), minimum: Number.NaN } as never)).toBe(false);
    expect(definitionReady({ ...(ready as object), maximum: Number.NaN } as never)).toBe(false);
    expect(definitionReady({ ...(ready as object), minimum: 5, maximum: 5 } as never)).toBe(true);
  });

  it('SRS-8-25 steps 3 - 4: the revision is simulated, then submitted with a remark; a refusal is shown', async () => {
    let current = detail();
    let submission: unknown = problem(409, 'WR-409-STATE', ['DEFINED', 'SIMULATED']);
    const calls = backend({
      ...LISTS,
      'GET /v1/masters/circulars/current': () => [],
      'GET /v1/wage-revisions?': (url) => paged([SUMMARY], 1, url),
      'GET /v1/wage-revisions/4': () => current,
      'GET /v1/wage-revisions/4/members?': (url) =>
        paged(current.members ? [MEMBER] : [], current.members, url),
      'POST /v1/wage-revisions/4/simulations': () => {
        current = detail({
          summary: { ...SUMMARY, status: 'SIMULATED', step: 3 },
          steps: steps(3),
          members: 1,
          estimatedArrear: 3760,
        });
        return current;
      },
      'POST /v1/wage-revisions/4/submissions': () => submission,
    });
    wrap(<WageRevisions />, me(['I-25:VIEW', 'I-25:CREATE', 'I-25:EXPORT']));
    const table = await screen.findByRole('table', { name: t('screen.I-25') });
    expect(await within(table).findByText(t('i25.stepLabel', [2, t('i25.stepName.2')]))).toBeTruthy();
    await openRow(table);
    const panel = await screen.findByRole('list', { name: t('i25.steps') });
    expect(within(panel).getByText(new RegExp(t('i25.step.SIMULATE')))).toBeTruthy();
    expect(await screen.findByText(t('i25.noMembers'))).toBeTruthy();
    const submit = screen.getByRole('button', { name: t('common.submitForApproval') });
    expect(submit.getAttribute('title')).toBe(t('i25.why.submit'));
    await userEvent.click(screen.getByRole('button', { name: t('action.i25.simulate') }));
    expect(await screen.findByText(t('i25.simulated'))).toBeTruthy();
    const members = await screen.findByRole('table', { name: t('i25.members') });
    expect(within(members).getByText('DH001 - Delhi Division')).toBeTruthy();
    expect(submit.getAttribute('title')).toBe(t('i25.why.remark'));
    const reasons = screen.getAllByLabelText(t('common.reason'));
    await userEvent.type(reasons[reasons.length - 1] as HTMLElement, 'Order');
    await userEvent.click(submit);
    expect(await screen.findByText(t('error.WR-409-STATE', ['DEFINED', 'SIMULATED']))).toBeTruthy();
    submission = { status: 202, body: { approvalId: 8, status: 'PENDING_APPROVAL' } };
    await userEvent.click(submit);
    expect(await screen.findByText(t('i25.submitted'))).toBeTruthy();
    expect(sent(calls, 'POST', '/submissions').at(-1)).toEqual({ remark: 'Order' });
    expect(screen.getAllByRole('button', { name: /export/i }).length).toBeGreaterThan(0);
    await userEvent.click(screen.getByRole('button', { name: t('common.close') }));
    expect(screen.queryByRole('list', { name: t('i25.steps') })).toBeNull();
  });

  it('SRS-8-25: a unit desk reads the revision but defines and simulates nothing', async () => {
    backend({
      'GET /v1/wage-revisions?': (url) => paged([{ ...SUMMARY, status: 'APPROVED', step: 4 }], 1, url),
      'GET /v1/wage-revisions/4': () =>
        detail({ summary: { ...SUMMARY, status: 'APPROVED' }, steps: steps(4) }),
      'GET /v1/wage-revisions/4/members?': (url) => paged([MEMBER], 1, url),
    });
    wrap(<WageRevisions />, me(['I-25:VIEW']));
    expect(screen.queryByRole('button', { name: t('action.i25.define') })).toBeNull();
    const table = await screen.findByRole('table', { name: t('screen.I-25') });
    expect(await within(table).findByText(t('i25.status.APPROVED'))).toBeTruthy();
    await openRow(table);
    const simulate = await screen.findByRole('button', { name: t('action.i25.simulate') });
    expect(simulate).toBeDisabled();
    expect(simulate.getAttribute('title')).toBe(t('reason.i25.hqDefinerOnly'));
    // No file for a role without the right: the drawn Export says so and nothing is sent.
    const drawn = screen.getAllByRole('button', { name: /export/i });
    expect(drawn).toHaveLength(1);
    expect(drawn[0]?.getAttribute('data-guard')).toBe(t('i25.why.noExport'));
  });

  it('I-25 l.3172: the statuses read in the source words; Apply Fixation is disabled with its reason before approval', async () => {
    expect(t('i25.status.PAY_FIXED')).toBe('Fixed');
    expect(t('i25.status.COMPLETED')).toBe('Arrears paid');
    backend({
      ...LISTS,
      'GET /v1/masters/circulars/current': () => [],
      'GET /v1/wage-revisions?': (url) => paged([{ ...SUMMARY, status: 'SIMULATED', step: 3 }], 1, url),
      'GET /v1/wage-revisions/4': () =>
        detail({ summary: { ...SUMMARY, status: 'SIMULATED' }, steps: steps(3) }),
      'GET /v1/wage-revisions/4/members?': (url) => paged([MEMBER], 1, url),
    });
    wrap(<WageRevisions />, me(['I-25:VIEW', 'I-25:CREATE']));
    const table = await screen.findByRole('table', { name: t('screen.I-25') });
    await openRow(table);
    const fix = await screen.findByRole('button', { name: t('action.i25.fix') });
    expect(fix).toBeDisabled();
    expect(fix.getAttribute('title')).toBe(t('i25.why.fix'));
  });

  it('SRS-8-25 steps 5 - 8: an approved revision is fixed in bulk; the status report follows', async () => {
    let current = detail({
      summary: { ...SUMMARY, status: 'APPROVED', step: 4 },
      steps: steps(4),
      members: 2,
    });
    const calls = backend({
      ...LISTS,
      'GET /v1/masters/circulars/current': () => [],
      'GET /v1/wage-revisions?': (url) => paged([{ ...SUMMARY, status: 'APPROVED', step: 4 }], 1, url),
      'GET /v1/wage-revisions/4': () => current,
      'GET /v1/wage-revisions/4/members?': (url) => paged([MEMBER], 1, url),
      'POST /v1/wage-revisions/4/fixations': () => {
        current = detail({
          summary: { ...SUMMARY, status: 'PAY_FIXED', step: 5 },
          steps: steps(5),
          members: 2,
        });
        return { fixed: 1, exceptions: 1, arrears: 0 };
      },
    });
    wrap(<WageRevisions />, me(['I-25:VIEW', 'I-25:CREATE', 'I-25:EXPORT']));
    const table = await screen.findByRole('table', { name: t('screen.I-25') });
    await openRow(table);
    await userEvent.click(await screen.findByRole('button', { name: t('action.i25.fix') }));
    expect(await screen.findByText(t('i25.fixed', [1, 1, 0]))).toBeTruthy();
    expect(sent(calls, 'POST', '/fixations')).toHaveLength(1);
    expect(await screen.findByRole('button', { name: t('i25.statusReport') })).toBeTruthy();
  });

  it('I-25 variants: a revision without an order, empty member amounts, a failed definition and fixation', async () => {
    let fix: unknown = problem(409, 'INC-409-STATE', ['SIMULATED', 'APPROVED']);
    backend({
      ...LISTS,
      'GET /v1/masters/circulars/current': () => [
        { id: 9, number: 'HQ/WR/2026', subject: null, category: 'WAGE_REVISION' },
      ],
      'GET /v1/wage-revisions?': (url) => paged([{ ...SUMMARY, order: null, status: 'APPROVED' }], 1, url),
      'GET /v1/wage-revisions/4': () =>
        detail({ summary: { ...SUMMARY, order: null, status: 'APPROVED' }, maximum: 90000, steps: steps(4) }),
      'GET /v1/wage-revisions/4/members?': (url) =>
        paged([{ ...MEMBER, oldPay: null, newPay: null, estimate: null }], 1, url),
      'POST /v1/wage-revisions': () => problem(422, 'WR-422-DEFINITION', ['the reason']),
      'POST /v1/wage-revisions/4/fixations': () => fix,
    });
    wrap(<WageRevisions />, me(['I-25:VIEW', 'I-25:CREATE']));
    const table = await screen.findByRole('table', { name: t('screen.I-25') });
    expect(await within(table).findByText('—')).toBeTruthy();
    await openDefine();
    await userEvent.type(screen.getByLabelText(t('i25.name')), 'Revision');
    await userEvent.selectOptions(
      screen.getAllByLabelText(t('i25.order'))[0] as HTMLElement,
      await screen.findByRole('option', { name: 'HQ/WR/2026' }),
    );
    const ida = screen.getByLabelText(t('i25.type.STAFF_IDA'));
    await userEvent.click(ida);
    await userEvent.click(screen.getByLabelText(t('i25.type.STAFF_CDA')));
    await userEvent.click(ida);
    await userEvent.type(screen.getAllByLabelText(t('i25.effective'))[0] as HTMLElement, '2026-08-01');
    await userEvent.selectOptions(screen.getAllByLabelText(t('i25.fitment'))[0] as HTMLElement, 'FACTOR');
    await userEvent.selectOptions(screen.getAllByLabelText(t('i25.fitment'))[0] as HTMLElement, '');
    await userEvent.selectOptions(screen.getAllByLabelText(t('i25.fitment'))[0] as HTMLElement, 'FACTOR');
    await userEvent.type(screen.getByLabelText(t('i25.value')), '1.2');
    await userEvent.type(screen.getAllByLabelText(t('i25.minimum'))[0] as HTMLElement, '10');
    await userEvent.type(screen.getAllByLabelText(t('i25.maximum'))[0] as HTMLElement, '90000');
    await userEvent.type(screen.getAllByLabelText(t('common.reason'))[0] as HTMLElement, 'Order');
    await userEvent.click(screen.getByRole('button', { name: t('action.i25.define') }));
    expect(await screen.findByText(t('error.WR-422-DEFINITION', ['the reason']))).toBeTruthy();
    await openRow(table);
    const members = await screen.findByRole('table', { name: t('i25.members') });
    expect(within(members).getAllByText('—')).toHaveLength(3);
    await userEvent.click(screen.getByRole('button', { name: t('action.i25.fix') }));
    expect(await screen.findByText(t('error.INC-409-STATE', ['SIMULATED', 'APPROVED']))).toBeTruthy();
    fix = { fixed: 0, exceptions: 0, arrears: 0 };
  });
  it('SRS-8-25: a cleared order or value is none and the definition waits for it', async () => {
    backend({
      ...LISTS,
      'GET /v1/masters/circulars/current': () => [
        { id: 9, number: 'HQ/WR/2026', subject: 'Pay revision', category: 'WAGE_REVISION' },
      ],
      'GET /v1/wage-revisions?': (url) => paged([], 0, url),
    });
    wrap(<WageRevisions />, me(['I-25:VIEW', 'I-25:CREATE']));
    await openDefine();
    const order = screen.getAllByLabelText(t('i25.order'))[0] as HTMLSelectElement;
    await userEvent.selectOptions(
      order,
      await screen.findByRole('option', { name: 'HQ/WR/2026 - Pay revision' }),
    );
    await userEvent.selectOptions(order, '');
    expect(order.value).toBe('');
    await userEvent.type(screen.getByLabelText(t('i25.value')), '1.2');
    await userEvent.clear(screen.getByLabelText(t('i25.value')));
    const define = screen.getByRole('button', { name: t('action.i25.define') });
    expect(define).toBeDisabled();
    expect(define.getAttribute('title')).toBe(t('i25.why.define'));
  });

  it('I-25 field table (SRS l.3164): only an Active circular of category Wage Revision is offered as the order', async () => {
    expect(
      revisionOrders([
        { id: 1, category: 'DA' },
        { id: 2, category: 'WAGE_REVISION' },
        { id: 3, category: null },
        { id: 4, category: 'wage_revision' },
      ]).map((o) => o.id),
    ).toEqual([2]);
    expect(revisionOrders([])).toEqual([]);
    backend({
      ...LISTS,
      'GET /v1/masters/circulars/current': () => [
        { id: 9, number: 'HQ/WR/2026', subject: 'Pay revision', category: 'WAGE_REVISION' },
        { id: 10, number: 'HQ/DA/2026', subject: 'DA revision', category: 'DA' },
        { id: 11, number: 'HQ/NC/2026', subject: 'No category', category: null },
      ],
      'GET /v1/wage-revisions?': (url) => paged([], 0, url),
    });
    wrap(<WageRevisions />, me(['I-25:VIEW', 'I-25:CREATE']));
    await openDefine();
    await screen.findByRole('option', { name: /HQ\/WR\/2026/ });
    expect(screen.queryByRole('option', { name: /HQ\/DA\/2026/ })).toBeNull();
    expect(screen.queryByRole('option', { name: /HQ\/NC\/2026/ })).toBeNull();
    expect(screen.queryByTestId('i25-no-order')).toBeNull();
  });

  it('I-25: with no Wage Revision circular in force the order field says so; a DA order is not offered', async () => {
    backend({
      ...LISTS,
      'GET /v1/masters/circulars/current': () => [
        { id: 10, number: 'HQ/DA/2026', subject: 'DA revision', category: 'DA' },
      ],
      'GET /v1/wage-revisions?': (url) => paged([], 0, url),
    });
    wrap(<WageRevisions />, me(['I-25:VIEW', 'I-25:CREATE']));
    await openDefine();
    expect((await screen.findByTestId('i25-no-order')).textContent).toBe(t('i25.noRevisionOrder'));
    expect(screen.queryByRole('option', { name: /HQ\/DA\/2026/ })).toBeNull();
  });

  it('I-25: a server refusal of an order of another category is shown and nothing is defined', async () => {
    backend({
      ...LISTS,
      'GET /v1/masters/circulars/current': () => [
        { id: 9, number: 'HQ/WR/2026', subject: 'Pay revision', category: 'WAGE_REVISION' },
      ],
      'GET /v1/wage-revisions?': (url) => paged([], 0, url),
      'POST /v1/wage-revisions': () =>
        problem(422, 'MST-422-CIRCULAR-CATEGORY', ['HQ/WR/2026', 'DA', 'WAGE_REVISION']),
    });
    wrap(<WageRevisions />, me(['I-25:VIEW', 'I-25:CREATE']));
    await openDefine();
    await userEvent.type(await screen.findByLabelText(t('i25.name')), 'Pay revision');
    await userEvent.selectOptions(
      screen.getAllByLabelText(t('i25.order'))[0] as HTMLElement,
      await screen.findByRole('option', { name: /HQ\/WR\/2026/ }),
    );
    await userEvent.click(screen.getByLabelText(t('i25.type.STAFF_IDA')));
    await userEvent.type(screen.getByLabelText(t('i25.effective')), '2026-08-01');
    await userEvent.selectOptions(screen.getByLabelText(t('i25.fitment')), 'PERCENTAGE');
    await userEvent.type(screen.getByLabelText(t('i25.value')), '3');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Order');
    await userEvent.click(screen.getByRole('button', { name: t('action.i25.define') }));
    expect(
      await screen.findByText(t('error.MST-422-CIRCULAR-CATEGORY', ['HQ/WR/2026', 'DA', 'WAGE_REVISION'])),
    ).toBeTruthy();
    expect(screen.queryByText(t('i25.defined'))).toBeNull();
  });

  it('an empty list says so', async () => {
    backend({ 'GET /v1/wage-revisions?': (url) => paged([], 0, url) });
    wrap(<WageRevisions />, me(['I-25:VIEW']));
    expect(await screen.findByText(t('i25.none'))).toBeTruthy();
  });
});

describe('I-25 at an office (g4)', () => {
  it('US-GAP-247 / SRS-8-25: a Payroll Maker of a unit holds the right but defines nothing away from Headquarters', async () => {
    backend({
      ...LISTS,
      'GET /v1/masters/circulars/current': () => [],
      'GET /v1/wage-revisions?': (url) => paged([SUMMARY], 1, url),
      'GET /v1/wage-revisions/4': () => detail(),
      'GET /v1/wage-revisions/4/members?': (url) => paged([], 0, url),
    });
    wrap(<WageRevisions />, me(['I-25:VIEW', 'I-25:CREATE'], { atHeadquarters: false }));
    const table = await screen.findByRole('table', { name: t('screen.I-25') });
    const define = screen.getByRole('button', { name: t('i25.act.define') });
    expect(define).toBeDisabled();
    expect(define.getAttribute('data-why')).toBe(t('i25.guard.define'));
    await openRow(table);
    const simulate = await screen.findByRole('button', { name: t('action.i25.simulate') });
    expect(simulate).toBeDisabled();
    expect(simulate.getAttribute('title')).toBe(t('reason.i25.hqDefinerOnly'));
  });
});

describe('I-25 as Wireframes v6.1 draws it (OD-121)', () => {
  const FIXED = {
    ...SUMMARY,
    id: 7,
    code: 'WR-2026-02',
    status: 'PAY_FIXED',
    step: 7,
    scope: 'SELECTED',
    rounding: null,
  };
  const BOARD = {
    events: 2,
    approved: 1,
    inSimulation: 1,
    fixedCode: 'WR-2026-02',
    fixedName: 'IDA staff pay revision',
    membersFixed: 4112,
    exceptions: 37,
    arrearsPaid: 18400000,
    officesDone: 61,
    offices: 74,
  };
  const OFFICES = [
    { office: 'DO Karnal', members: 412, fixed: 412, pending: 0, exception: 0, paid: 412 },
    { office: 'DO Ambala', members: 388, fixed: 380, pending: 0, exception: 8, paid: 0 },
    { office: 'DO Rohtak', members: 296, fixed: 296, pending: 0, exception: 0, paid: 100 },
  ];

  it('SRS-8-25: the rules of the drawn objects', () => {
    expect(isFixed('PAY_FIXED')).toBe(true);
    expect(isFixed('COMPLETED')).toBe(true);
    expect(isFixed('APPROVED')).toBe(false);
    expect(isOtpApproved('APPROVED')).toBe(true);
    expect(isOtpApproved('PAYING')).toBe(true);
    expect(isOtpApproved('SIMULATED')).toBe(false);
    expect(revisionTone('SIMULATED')).toBe('p-warn');
    expect(revisionTone('REJECTED')).toBe('p-bad');
    expect(revisionTone('CANCELLED')).toBe('p-bad');
    expect(revisionTone('DEFINED')).toBe('p-gray');
    expect(revisionTone('DRAFT')).toBe('p-gray');
    expect(revisionTone('APPROVED')).toBe('p-ok');
    expect(officeState(OFFICES[0] as never)).toBe('PAID');
    expect(officeState(OFFICES[1] as never)).toBe('EXCEPTIONS');
    expect(officeState(OFFICES[2] as never)).toBe('PENDING');
  });

  it('SRS-8-25 step 8: the tiles and the DO-wise report of the fixed revision, chosen first; a row changes it', async () => {
    const calls = backend({
      'GET /v1/wage-revisions/summary': () => BOARD,
      'GET /v1/wage-revisions?': (url) => paged([SUMMARY, FIXED], 2, url),
      'GET /v1/wage-revisions/7/offices': (url) => paged(OFFICES, 3, url),
      'GET /v1/wage-revisions/4/offices': (url) => paged([], 0, url),
      'GET /v1/wage-revisions/4': () => detail(),
      'GET /v1/wage-revisions/4/members?': (url) => paged([], 0, url),
    });
    wrap(<WageRevisions />, me(['I-25:VIEW', 'I-25:EXPORT']));
    expect(await screen.findByText('4,112')).toBeTruthy();
    expect(screen.getByText(t('i25.tile.eventsSub', [1, 1]))).toBeTruthy();
    expect(screen.getByText(t('i25.tile.fixedSub', ['IDA staff pay revision', 'WR-2026-02']))).toBeTruthy();
    expect(screen.getByText(t('i25.tile.exceptionsSub'))).toBeTruthy();
    expect(screen.getByText(t('i25.tile.arrearsSub', [61, 74]))).toBeTruthy();
    const report = await screen.findByRole('table', { name: t('i25.report.title', ['WR-2026-02']) });
    expect(await within(report).findByText(t('i25.report.state.EXCEPTIONS'))).toBeTruthy();
    expect(within(report).getByText(t('i25.report.state.PAID'))).toBeTruthy();
    expect(within(report).getByText(t('i25.report.state.PENDING'))).toBeTruthy();
    expect(screen.getByRole('button', { name: t('i25.act.report') }).getAttribute('data-guard')).toBeNull();
    const list = screen.getByRole('table', { name: t('screen.I-25') });
    expect(within(list).getByText(t('i25.scope.SELECTED'))).toBeTruthy();
    expect(within(list).getAllByText(t('i25.otp'))).toHaveLength(1);
    await openRow(list);
    expect(await screen.findByRole('table', { name: t('i25.report.title', ['WR-2026-0004']) })).toBeTruthy();
    expect(await screen.findByText(t('i25.report.none'))).toBeTruthy();
    expect(calls.some((c) => c.url.includes('/4/offices'))).toBe(true);
    expect(screen.getByRole('button', { name: t('i25.act.report') }).getAttribute('data-guard')).toBe(
      t('i25.why.report'),
    );
  });

  it('SRS-G-25: each role sees the three actions: locked with the drawn reason, or explained for the drawn role', async () => {
    backend({ 'GET /v1/wage-revisions?': (url) => paged([SUMMARY], 1, url) });
    const reader = wrap(<WageRevisions />, me(['I-25:VIEW']));
    await screen.findByRole('table', { name: t('screen.I-25') });
    expect(screen.getByRole('button', { name: t('i25.act.approve') }).getAttribute('data-why')).toBe(
      t('i25.guard.approve'),
    );
    reader.unmount();
    const admin = wrap(<WageRevisions />, me(['I-25:VIEW'], { roles: ['SYSTEM_ADMINISTRATOR'] }));
    const define = await screen.findByRole('button', { name: t('i25.act.define') });
    expect(define).toBeEnabled();
    expect(define.getAttribute('data-guard')).toBe(t('i25.why.defineAdmin'));
    admin.unmount();
    const office = wrap(<WageRevisions />, me(['I-25:VIEW', 'I-25:APPROVE'], { atHeadquarters: false }));
    const approve = await screen.findByRole('button', { name: t('i25.act.approve') });
    expect(approve.getAttribute('data-guard')).toBe(t('i25.why.approveHq'));
    office.unmount();
    wrap(<WageRevisions />, me(['I-25:VIEW', 'I-25:APPROVE']));
    expect((await screen.findByRole('link', { name: t('i25.act.approve') })).getAttribute('href')).toMatch(
      /s=W-01$/,
    );
  });

  it('an empty list has no revision to report; the keyboard opens a row', async () => {
    backend({ 'GET /v1/wage-revisions?': (url) => paged([], 0, url) });
    const none = wrap(<WageRevisions />, me(['I-25:VIEW', 'I-25:EXPORT']));
    expect(await screen.findByRole('table', { name: t('i25.report.titleNone') })).toBeTruthy();
    none.unmount();
    backend({
      'GET /v1/wage-revisions?': (url) =>
        paged([{ ...SUMMARY, rule: null, scope: null, effectiveFrom: null, ruleValue: null }], 1, url),
      'GET /v1/wage-revisions/4': () => detail(),
      'GET /v1/wage-revisions/4/members?': (url) => paged([], 0, url),
    });
    wrap(<WageRevisions />, me(['I-25:VIEW']));
    const row = (await screen.findByText(SUMMARY.code)).closest('tr') as HTMLElement;
    expect(within(row).getAllByText('—')).toHaveLength(3);
    row.focus();
    await userEvent.keyboard('x');
    expect(screen.queryByRole('list', { name: t('i25.steps') })).toBeNull();
    await userEvent.keyboard('{Enter}');
    expect(await screen.findByRole('list', { name: t('i25.steps') })).toBeTruthy();
  });
});
