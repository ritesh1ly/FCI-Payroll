import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { IncrementFixation, PayScales } from '@/modules/increment';
import {
  caseReady,
  entersBasic,
  parseStages,
  selectable,
  versionReady,
} from '@/modules/increment/business/incrementRules';
import { backend, me, paged, problem, sent, t, wrap } from './support/harness';
import { date } from '@/shared/ui';
import { badStageRow, stagesOfRows } from '@/modules/increment/business/incrementRules';

/**
 * Module 17 (plan 17): I-05 Increment & Pay Fixation - the due list with its proposals and flags,
 * the run, a case previewed then submitted, the Basic history - and M-03 Pay Scales & Grades with
 * a new version; every action disabled with its reason.
 */

afterEach(() => vi.unstubAllGlobals());

const DUE = {
  employeeId: 11,
  eis: '109991',
  name: 'Vikram Kumar',
  office: 'DH001 - Delhi Division',
  dueDate: '2026-10-01',
  oldBasic: 62400,
  revisedBasic: 64300,
  flags: [],
  barredBy: [],
  pending: false,
  incrementType: 'ANNUAL',
  stagnation: null,
  stagnationTest: null,
  payScaleCode: 'E-4',
  nextDueDate: '2027-10-01',
};

const FLAGGED = {
  ...DUE,
  employeeId: 12,
  eis: '109992',
  name: 'Meera Nair',
  revisedBasic: 50000,
  flags: ['STAGES_NOT_CONFIGURED', 'NOT_CONFIGURED'],
};

const CASE = {
  eis: '109992',
  caseType: 'INDIVIDUAL_REVISION' as const,
  effectiveDate: '2026-08-01',
  basic: 52000,
  orderNumber: 'EST/17',
  orderDate: '2026-10-20',
  issuingAuthority: 'GM (P)',
  reason: 'Order',
};

describe('I-05 Increment & Pay Fixation', () => {
  it('SRS-13-5 / 13-18: the rules decide what the screen offers', () => {
    expect(selectable(DUE as never)).toBe(true);
    expect(selectable(FLAGGED as never)).toBe(false);
    expect(selectable({ ...DUE, flags: ['STAGES_NOT_CONFIGURED'] } as never)).toBe(true);
    expect(selectable({ ...DUE, pending: true } as never)).toBe(false);
    expect(entersBasic('INDIVIDUAL_REVISION')).toBe(true);
    expect(entersBasic('PROMOTION_FIXATION')).toBe(false);
    expect(caseReady(CASE)).toBe(true);
    expect(caseReady({ ...CASE, orderNumber: ' ' })).toBe(false);
    expect(caseReady({ ...CASE, basic: undefined })).toBe(false);
    expect(caseReady({ ...CASE, caseType: 'DATE_CORRECTION', basic: undefined })).toBe(false);
    expect(caseReady({ ...CASE, caseType: 'DATE_CORRECTION', correctedDueDate: '2026-11-01' })).toBe(true);
    expect(parseStages('60000, 62400 64300')).toEqual([60000, 62400, 64300]);
    expect(parseStages('60000, x')).toEqual([]);
    const v = {
      min: 60000,
      max: 70000,
      effectiveFrom: '2027-01-01',
      circularId: 3,
      stages: [60000, 62400],
      reason: 'Order',
      wageRevision: 'WR-2026-01',
    };
    expect(versionReady(v)).toBe(true);
    expect(versionReady({ ...v, stages: [80000] })).toBe(false);
    expect(versionReady({ ...v, min: 70000 })).toBe(false);
  });

  it('I-05 (review 17-1): the default month is the business month in India, not the browser clock, at each boundary', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    try {
      for (const [instant, month] of [
        ['2026-10-31T18:29:59Z', '2026-10'], // 23:59:59 IST on 31 October
        ['2026-10-31T18:30:00Z', '2026-11'], // 00:00 IST on 1 November, still October in UTC
        ['2026-12-31T19:00:00Z', '2027-01'], // the year turns in India first
      ] as const) {
        vi.setSystemTime(new Date(instant));
        const calls = backend({
          'GET /v1/document-types': () => [],
          'GET /v1/increments/due?': (url) => paged([], 0, url),
        });
        const view = wrap(<IncrementFixation />, me(['I-05:VIEW']));
        expect(((await screen.findByLabelText(t('i05.month'))) as HTMLInputElement).value).toBe(month);
        await waitFor(() => expect(calls.some((c) => c.url.includes(`month=${month}`))).toBe(true));
        view.unmount();
      }
    } finally {
      vi.useRealTimers();
    }
  });

  it('SRS-13-21: the due list proposes and flags; HR submits the selected members', async () => {
    const calls = backend({
      'GET /v1/document-types': () => [{ code: 'OTHER', name: 'Other document', referenceRequired: false }],
      'GET /v1/increments/due?': (url) => paged([DUE, FLAGGED], 2, url),
      'POST /v1/increments/submissions': () => ({ approvalIds: [41], refused: [] }),
    });
    wrap(<IncrementFixation />, me(['I-05:VIEW', 'I-05:SUBMIT', 'I-05:EXPORT']));
    const table = await screen.findByRole('table', { name: t('i05.due') });
    expect(within(table).getByText(t('i05.flag.NOT_CONFIGURED'))).toBeTruthy();
    const flagged = within(table).getByLabelText(t('i05.selectMember', ['109992']));
    expect(flagged).toBeDisabled();
    expect(flagged.getAttribute('title')).toBe(t('i05.why.flagged'));
    const submit = screen.getByRole('button', { name: new RegExp(t('action.i05.submit')) });
    expect(submit.getAttribute('title')).toBe(t('i05.why.none'));
    await userEvent.click(within(table).getByLabelText(t('i05.selectMember', ['109991'])));
    expect(submit.getAttribute('title')).toBe(t('i05.why.remark'));
    await userEvent.type(screen.getAllByLabelText(t('common.reason'))[0] as HTMLElement, 'Run');
    await userEvent.click(submit);
    expect(await screen.findByText(t('i05.submitted', [1]))).toBeTruthy();
    expect(sent(calls, 'POST', '/v1/increments/submissions').at(-1)).toEqual({
      employeeIds: [11],
      remark: 'Run',
    });
    expect(screen.getAllByRole('button', { name: /export|report/i }).length).toBeGreaterThan(0);
  });

  it('I-05 l.4001-4002: each member shows the HRMS pay scale, the increment type and the next due date', async () => {
    backend({
      'GET /v1/document-types': () => [],
      'GET /v1/increments/due?': (url) =>
        paged(
          [DUE, { ...DUE, employeeId: 13, eis: '109993', payScaleCode: null, incrementType: 'STAGNATION' }],
          2,
          url,
        ),
    });
    wrap(<IncrementFixation />, me(['I-05:VIEW']));
    const table = await screen.findByRole('table', { name: t('i05.due') });
    expect(within(table).getByText(t('i05.payScale'))).toBeTruthy();
    expect(within(table).getByText('E-4')).toBeTruthy();
    expect(within(table).getByText(t('i05.type.STAGNATION'))).toBeTruthy();
    expect(within(table).getAllByText(date('2027-10-01')).length).toBe(2);
  });

  it('UI audit #30: a cleared month asks for one; nothing is fetched with an empty month and no export is offered', async () => {
    const calls = backend({
      'GET /v1/document-types': () => [],
      'GET /v1/increments/due?': (url) => paged([DUE], 1, url),
    });
    wrap(<IncrementFixation />, me(['I-05:VIEW', 'I-05:EXPORT']));
    await screen.findByRole('table', { name: t('i05.due') });
    fireEvent.change(screen.getByLabelText(t('i05.month')), { target: { value: '' } });
    expect(await screen.findByText(t('i05.why.month'))).toBeTruthy();
    expect(calls.some((c) => c.url.includes('month=&') || c.url.endsWith('month='))).toBe(false);
  });

  it('SRS-13-18: a case is previewed, then submitted; a refusal is shown; refused members listed', async () => {
    let answer: unknown = problem(422, 'INC-422-FLAGGED', ['109992', 'ABOVE_MAXIMUM']);
    const calls = backend({
      'GET /v1/document-types': () => [{ code: 'OTHER', name: 'Other document', referenceRequired: false }],
      'GET /v1/increments/due?': (url) => paged([DUE], 1, url),
      'POST /v1/increments/submissions': () => ({
        approvalIds: [],
        refused: [{ eis: '109991', reason: 'INC-409-DUPLICATE 2026-27' }],
      }),
      'POST /v1/pay-fixation-cases/previews': () => answer,
      'GET /v1/basic-history?': () => [
        {
          effectiveFrom: '2025-07-01',
          sequence: 1,
          effectiveTo: null,
          event: 'ANNUAL_INCREMENT',
          basic: 50000,
          oldBasic: 48500,
          source: 'SYS',
          reason: 'Annual increment 2025',
          order: 'EST/9',
          by: 'checker.delhi',
          maker: 'maker.delhi',
        },
      ],
      'POST /v1/pay-fixation-cases/orders': () => ({
        status: 201,
        body: { attachmentId: 31, provisionalKey: 'PROVISIONAL:7c9e6679-7425-40de-944b-e07fc1f90ae7' },
      }),
      'POST /v1/pay-fixation-cases': () => ({
        status: 202,
        body: { approvalId: 9, status: 'PENDING_APPROVAL' },
      }),
    });
    wrap(<IncrementFixation />, me(['I-05:VIEW', 'I-05:SUBMIT']));
    const table = await screen.findByRole('table', { name: t('i05.due') });
    await userEvent.click(within(table).getByLabelText(t('i05.selectMember', ['109991'])));
    await userEvent.type(screen.getAllByLabelText(t('common.reason'))[0] as HTMLElement, 'Run');
    await userEvent.click(screen.getByRole('button', { name: new RegExp(t('action.i05.submit')) }));
    expect(await screen.findByText(/INC-409-DUPLICATE/)).toBeTruthy();

    const preview = screen.getByRole('button', { name: t('action.i05.preview') });
    expect(preview.getAttribute('title')).toBe(t('i05.why.case'));
    await userEvent.type(screen.getAllByLabelText(t('m01.eis'))[0] as HTMLElement, '109992');
    await userEvent.selectOptions(screen.getByLabelText(t('i05.caseType')), 'INDIVIDUAL_REVISION');
    await userEvent.type(screen.getAllByLabelText(t('i05.effective'))[0] as HTMLElement, '2026-08-01');
    await userEvent.type(screen.getAllByLabelText(t('i05.revisedBasic'))[0] as HTMLElement, '52000');
    await userEvent.type(screen.getByLabelText(t('i05.orderNumber')), 'EST/17');
    await userEvent.type(screen.getByLabelText(t('i05.orderDate')), '2026-10-20');
    await userEvent.type(screen.getByLabelText(t('i05.authority')), 'GM (P)');
    const reasons = screen.getAllByLabelText(t('common.reason'));
    await userEvent.type(reasons[reasons.length - 1] as HTMLElement, 'Order');
    const submitCase = screen.getByRole('button', { name: t('common.submitForApproval') });
    expect(submitCase.getAttribute('title')).toBe(t('i05.why.preview'));
    await userEvent.click(preview);
    expect(await screen.findByText(t('error.INC-422-FLAGGED', ['109992', 'ABOVE_MAXIMUM']))).toBeTruthy();
    answer = {
      ...CASE,
      employeeId: 12,
      oldBasic: 50000,
      revisedBasic: 52000,
      oldDueDate: null,
      correctedDueDate: null,
      test: null,
    };
    await userEvent.click(preview);
    expect(await screen.findByRole('region', { name: t('i05.proposal') })).toBeTruthy();
    // SRS-13-26: before save, the proposed Basic is shown with the member's Basic Pay history.
    const before = screen.getByRole('region', { name: t('i05.memberHistory') });
    expect(await within(before).findByText('Annual increment 2025')).toBeTruthy();
    expect(sent(calls, 'GET', '/v1/basic-history').length).toBeGreaterThan(0);
    // SRS-13-18 (US-GAP-10): not without the order copy.
    expect(submitCase).toBeDisabled();
    expect(submitCase.getAttribute('title')).toBe(t('i05.why.copy'));
    await userEvent.selectOptions(screen.getByLabelText(t('ord.type')), 'OTHER');
    await userEvent.upload(
      screen.getByLabelText(t('i05.orderCopy')),
      new File(['%PDF-1.7'], 'order.pdf', { type: 'application/pdf' }),
    );
    await userEvent.click(submitCase);
    expect(await screen.findByText(t('i05.caseSubmitted'))).toBeTruthy();
    expect(sent(calls, 'POST', '/v1/pay-fixation-cases').at(-1)).toMatchObject({
      eis: '109992',
      basic: 52000,
      orderAttachmentId: 31,
      orderProvisionalKey: 'PROVISIONAL:7c9e6679-7425-40de-944b-e07fc1f90ae7',
    });
  });

  it('SRS-13-27 / 13-24: an observation refixation names its observation; a reversal explains itself', async () => {
    const calls = backend({
      'GET /v1/document-types': () => [{ code: 'OTHER', name: 'Other document', referenceRequired: false }],
      'GET /v1/increments/due?': (url) => paged([], 0, url),
      'POST /v1/pay-fixation-cases/previews': () => ({
        ...CASE,
        caseType: 'OBSERVATION_REFIXATION',
        employeeId: 12,
        oldBasic: 50000,
        revisedBasic: 52000,
        oldDueDate: null,
        correctedDueDate: null,
        test: null,
        reversedEntry: null,
      }),
      'GET /v1/basic-history?': () => [],
    });
    wrap(<IncrementFixation />, me(['I-05:VIEW', 'I-05:SUBMIT']));
    await screen.findByText(t('i05.noneDue'));
    await userEvent.type(screen.getAllByLabelText(t('m01.eis'))[0] as HTMLElement, '109992');
    await userEvent.selectOptions(screen.getByLabelText(t('i05.caseType')), 'REVERSE_ENTRY');
    expect(screen.getByText(t('i05.reverseNote'))).toBeTruthy();
    expect(screen.queryByLabelText(t('i05.revisedBasic'))).toBeNull();
    await userEvent.selectOptions(screen.getByLabelText(t('i05.caseType')), 'OBSERVATION_REFIXATION');
    expect(screen.queryByText(t('i05.reverseNote'))).toBeNull();
    await userEvent.type(screen.getAllByLabelText(t('i05.effective'))[0] as HTMLElement, '2026-08-01');
    await userEvent.type(screen.getAllByLabelText(t('i05.revisedBasic'))[0] as HTMLElement, '52000');
    await userEvent.type(screen.getByLabelText(t('i05.orderNumber')), 'AUD/17');
    await userEvent.type(screen.getByLabelText(t('i05.orderDate')), '2026-10-20');
    await userEvent.type(screen.getByLabelText(t('i05.authority')), 'Audit');
    const reasons = screen.getAllByLabelText(t('common.reason'));
    await userEvent.type(reasons[reasons.length - 1] as HTMLElement, 'Para 4');
    const preview = screen.getByRole('button', { name: t('action.i05.preview') });
    expect(preview).toBeDisabled();
    await userEvent.selectOptions(
      screen.getByLabelText(new RegExp(t('i05.observationSource'))),
      t('i05.observation.AUDIT'),
    );
    expect(preview).toBeDisabled();
    await userEvent.type(screen.getByLabelText(new RegExp(t('i05.observationReference'))), ' AUD/2026/4 ');
    expect(preview).toBeEnabled();
    await userEvent.click(preview);
    expect(await screen.findByRole('region', { name: t('i05.proposal') })).toBeTruthy();
    expect(sent(calls, 'POST', '/previews').at(-1)).toMatchObject({
      caseType: 'OBSERVATION_REFIXATION',
      observationSource: 'AUDIT',
      observationReference: 'AUD/2026/4',
    });
    await userEvent.selectOptions(screen.getByLabelText(new RegExp(t('i05.observationSource'))), '');
    expect(preview).toBeDisabled();
  });

  it('SRS-13-26: the Basic history of a member; a viewer submits nothing', async () => {
    backend({
      'GET /v1/document-types': () => [{ code: 'OTHER', name: 'Other document', referenceRequired: false }],
      'GET /v1/increments/due?': (url) => paged([DUE], 1, url),
      'GET /v1/basic-history?': () => [
        {
          effectiveFrom: '2026-10-01',
          sequence: 1,
          effectiveTo: null,
          event: 'INCREMENT',
          basic: 64300,
          oldBasic: 62400,
          source: 'CALC',
          reason: 'INCREMENT w.e.f. 2026-10-01',
          order: null,
          by: 'ddo.delhi@fci-demo.test',
          maker: 'hr.delhi@fci-demo.test',
        },
        {
          effectiveFrom: '2026-04-01',
          sequence: 1,
          effectiveTo: '2026-09-30',
          event: 'INCREMENT',
          basic: 62400,
          oldBasic: 60000,
          source: 'CALC',
          reason: 'INCREMENT w.e.f. 2026-04-01',
          order: null,
          by: 'ddo.delhi@fci-demo.test',
          maker: null,
          corrected: true,
        },
      ],
    });
    wrap(<IncrementFixation />, me(['I-05:VIEW']));
    const table = await screen.findByRole('table', { name: t('i05.due') });
    const box = within(table).getByLabelText(t('i05.selectMember', ['109991']));
    expect(box).toBeDisabled();
    expect(box.getAttribute('title')).toBe(t('reason.establishmentOnly'));
    expect(screen.queryByText(t('i05.case'))).toBeNull();
    await userEvent.type(screen.getByLabelText(t('m01.eis')), '109991');
    await userEvent.click(screen.getByRole('button', { name: t('i05.showHistory') }));
    const history = await screen.findByRole('table', { name: t('i05.history') });
    expect(within(history).getAllByText(t('i05.event.INCREMENT'))).toHaveLength(2);
    // SRS-13-26 (US-GAP-55): the Maker and the Checker of each event.
    expect(within(history).getByText('hr.delhi@fci-demo.test')).toBeTruthy();
    expect(within(history).getAllByText('ddo.delhi@fci-demo.test')).toHaveLength(2);
    // SRS-13-24: the old entry of a correction stays, marked as corrected (only that one).
    expect(within(history).getAllByText(t('i05.corrected'))).toHaveLength(1);
  });
  it('I-05 variants: empty amounts, a pending and a barred member, a deselect, a failed run, another month', async () => {
    const calls = backend({
      'GET /v1/document-types': () => [{ code: 'OTHER', name: 'Other document', referenceRequired: false }],
      'GET /v1/increments/due?': (url) =>
        paged(
          [
            { ...DUE, oldBasic: null, revisedBasic: null },
            { ...FLAGGED, employeeId: 13, eis: '109993', flags: ['BARRED'], barredBy: ['PEN-2026-00001'] },
            { ...DUE, employeeId: 14, eis: '109994', pending: true },
            {
              ...DUE,
              employeeId: 15,
              eis: '109995',
              oldBasic: 64300,
              revisedBasic: 65800,
              flags: ['STAGNATION'],
              incrementType: 'STAGNATION',
              stagnation: 'ELIGIBLE',
              stagnationTest: 'At maximum 64300 since 2024-10-01 (qualifying 12 months); 0 of 2 given',
            },
            {
              ...DUE,
              employeeId: 16,
              eis: '109996',
              oldBasic: 64300,
              flags: ['ABOVE_MAXIMUM'],
              stagnation: 'AT_CAP',
            },
          ],
          5,
          url,
        ),
      'POST /v1/increments/submissions': () => problem(409, 'INC-409-DUPLICATE', ['109991', '2026-27']),
    });
    wrap(<IncrementFixation />, me(['I-05:VIEW', 'I-05:SUBMIT']));
    const table = await screen.findByRole('table', { name: t('i05.due') });
    expect(within(table).getAllByText('—').length).toBeGreaterThanOrEqual(2);
    expect(within(table).getByText('PEN-2026-00001')).toBeTruthy();
    expect(within(table).getByText(t('i05.pending'))).toBeTruthy();
    // BR-INCR-STAG-001 / 007: the stagnation offer with its test is selectable; a member at the cap
    // is flagged with the reason and is not.
    expect(within(table).getByText(t('i05.flag.STAGNATION'))).toBeTruthy();
    expect(within(table).getByText(/0 of 2 given/)).toBeTruthy();
    expect(within(table).getByLabelText(t('i05.selectMember', ['109995']))).not.toBeDisabled();
    expect(within(table).getByText(t('i05.stag.AT_CAP'))).toBeTruthy();
    expect(within(table).getByLabelText(t('i05.selectMember', ['109996']))).toBeDisabled();
    expect(
      within(table)
        .getByLabelText(t('i05.selectMember', ['109994']))
        .getAttribute('title'),
    ).toBe(t('i05.why.pending'));
    const box = within(table).getByLabelText(t('i05.selectMember', ['109991']));
    await userEvent.click(box);
    await userEvent.click(box);
    expect(
      screen.getByRole('button', { name: new RegExp(t('action.i05.submit')) }).getAttribute('title'),
    ).toBe(t('i05.why.none'));
    await userEvent.click(box);
    await userEvent.type(screen.getAllByLabelText(t('common.reason'))[0] as HTMLElement, 'Run');
    await userEvent.click(screen.getByRole('button', { name: new RegExp(t('action.i05.submit')) }));
    expect(await screen.findByText(t('error.INC-409-DUPLICATE', ['109991', '2026-27']))).toBeTruthy();
    const month = screen.getByLabelText(t('i05.month'));
    await userEvent.clear(month);
    await userEvent.type(month, '2026-11');
    expect(calls.some((c) => c.url.includes('month=2026-11'))).toBe(true);
  });

  it('SRS-13-24: a date correction is previewed with its corrected date; the history shows ends and orders', async () => {
    backend({
      'GET /v1/document-types': () => [{ code: 'OTHER', name: 'Other document', referenceRequired: false }],
      'GET /v1/increments/due?': (url) => paged([], 0, url),
      'POST /v1/pay-fixation-cases/previews': () => ({
        employeeId: 12,
        eis: '109992',
        caseType: 'DATE_CORRECTION',
        effectiveDate: '2026-10-01',
        oldBasic: null,
        revisedBasic: null,
        oldDueDate: '2026-10-01',
        correctedDueDate: '2026-11-01',
        test: 'At maximum since 2020-01-01',
      }),
      'GET /v1/basic-history?': () => [
        {
          effectiveFrom: '2010-07-01',
          sequence: 1,
          effectiveTo: '2026-09-30',
          event: 'MIGRATION_OPENING',
          basic: 50000,
          oldBasic: null,
          source: 'SYS',
          reason: 'Opening',
          order: 'EST/1',
          by: 'migration',
          maker: null,
        },
      ],
    });
    wrap(<IncrementFixation />, me(['I-05:VIEW', 'I-05:SUBMIT', 'I-05:EXPORT']));
    expect(await screen.findByText(t('i05.noneDue'))).toBeTruthy();
    await userEvent.type(screen.getAllByLabelText(t('m01.eis'))[0] as HTMLElement, '109992');
    await userEvent.selectOptions(screen.getByLabelText(t('i05.caseType')), 'DATE_CORRECTION');
    await userEvent.type(screen.getAllByLabelText(t('i05.effective'))[0] as HTMLElement, '2026-10-01');
    await userEvent.type(screen.getByLabelText(t('i05.correctedDue')), '2026-11-01');
    await userEvent.type(screen.getByLabelText(t('i05.orderNumber')), 'EST/18');
    await userEvent.type(screen.getByLabelText(t('i05.orderDate')), '2026-10-20');
    await userEvent.type(screen.getByLabelText(t('i05.authority')), 'GM (P)');
    const reasons = screen.getAllByLabelText(t('common.reason'));
    await userEvent.type(reasons[reasons.length - 1] as HTMLElement, 'Wrong date');
    await userEvent.click(screen.getByRole('button', { name: t('action.i05.preview') }));
    const proposal = await screen.findByRole('region', { name: t('i05.proposal') });
    expect(within(proposal).getByText('At maximum since 2020-01-01')).toBeTruthy();
    await userEvent.selectOptions(screen.getByLabelText(t('i05.caseType')), '');
    expect(screen.queryByRole('region', { name: t('i05.proposal') })).toBeNull();
    const eis = screen.getAllByLabelText(t('m01.eis'));
    await userEvent.type(eis[eis.length - 1] as HTMLElement, '109992');
    await userEvent.click(screen.getByRole('button', { name: t('i05.showHistory') }));
    const history = await screen.findByRole('table', { name: t('i05.history') });
    expect(within(history).getByText('EST/1')).toBeTruthy();
    expect(within(history).getByText(t('i05.event.MIGRATION_OPENING'))).toBeTruthy();
    expect(screen.getByRole('button', { name: t('i05.historyReport') })).toBeTruthy();
  });
});

describe('M-03 Pay Scales & Grades', () => {
  const SCALE = {
    bandId: 3,
    code: 'E-4',
    name: 'IDA E-4',
    scaleType: 'IDA',
    min: 60000,
    max: 180000,
    stagnationAmount: null,
    effectiveFrom: '2017-01-01',
    stages: 37,
    pending: false,
    active: true,
  };

  it('SC-17-0030: a new scale is defined with a unique code, a name, its type and its first version', async () => {
    const calls = backend({
      'GET /v1/document-types': () => [{ code: 'OTHER', name: 'Other document', referenceRequired: false }],
      'GET /v1/pay-scales?': (url) => paged([{ ...SCALE, active: false, pending: true }], 1, url),
      'GET /v1/masters/circulars/current': () => [{ id: 3, number: 'HQ/PAY/2026', subject: 'Scales' }],
      'GET /v1/pay-scales/wage-revisions': () => [
        { code: 'WR-2026-01', name: 'Staff pay revision', payrollTypes: 'STAFF_CDA', status: 'DEFINED' },
      ],
      'POST /v1/pay-scales': () => ({
        status: 202,
        body: { approvalId: 6, status: 'PENDING_APPROVAL', aboveMaximum: [] },
      }),
    });
    wrap(<PayScales />, me(['M-03:VIEW', 'M-03:CREATE']));
    const table = await screen.findByRole('table', { name: t('screen.M-03') });
    expect(within(table).getByText(t('m03.inactive'))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('m03.newScale') }));
    expect(await screen.findByText(t('m03.newScaleTitle'))).toBeTruthy();
    const define = screen.getAllByRole('button', { name: t('m03.newScale') }).at(-1) as HTMLElement;
    expect(define).toBeDisabled();
    expect(define.getAttribute('title')).toBe(t('m03.why.newScale'));
    await userEvent.type(screen.getByLabelText(new RegExp(t('m03.code'))), 'C 1');
    await userEvent.type(screen.getByLabelText(new RegExp(t('m03.name'))), 'CDA Level 1');
    await userEvent.selectOptions(screen.getByLabelText(new RegExp(t('m03.type'))), 'CDA');
    await userEvent.type(screen.getAllByLabelText(t('m03.min'))[0] as HTMLElement, '60000');
    await userEvent.type(screen.getAllByLabelText(t('m03.max'))[0] as HTMLElement, '70000');
    await userEvent.type(screen.getAllByLabelText(t('m03.from'))[0] as HTMLElement, '2027-01-01');
    await userEvent.selectOptions(
      screen.getByLabelText(t('m03.circular')),
      await screen.findByRole('option', { name: /HQ\/PAY\/2026/ }),
    );
    await userEvent.type(screen.getByLabelText(t('m03.stageRow', [1])), '60000');
    await userEvent.click(screen.getByRole('button', { name: t('m03.addStage') }));
    await userEvent.type(screen.getByLabelText(t('m03.stageRow', [2])), '62400');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'New scale');
    await userEvent.selectOptions(
      screen.getByLabelText(t('m03.wageRevision')),
      await screen.findByRole('option', { name: 'WR-2026-01 - Staff pay revision' }),
    );
    // A code with a space is refused before it is sent.
    expect(define).toBeDisabled();
    await userEvent.clear(screen.getByLabelText(new RegExp(t('m03.code'))));
    await userEvent.type(screen.getByLabelText(new RegExp(t('m03.code'))), 'C-1');
    expect(define).toBeEnabled();
    await userEvent.click(define);
    expect(await screen.findByText(t('m03.defined'))).toBeTruthy();
    expect(sent(calls, 'POST', '/v1/pay-scales').at(-1)).toMatchObject({
      code: 'C-1',
      name: 'CDA Level 1',
      scaleType: 'CDA',
      version: { stages: [60000, 62400], circularId: 3, wageRevision: 'WR-2026-01' },
    });
    await userEvent.click(screen.getByRole('button', { name: t('common.close') }));
    expect(screen.queryByText(t('m03.newScaleTitle'))).toBeNull();
  });

  it('SC-17-0030: a reader is not offered a new scale', async () => {
    backend({ 'GET /v1/pay-scales?': (url) => paged([SCALE], 1, url) });
    wrap(<PayScales />, me(['M-03:VIEW']));
    await screen.findByRole('table', { name: t('screen.M-03') });
    expect(screen.queryByRole('button', { name: t('m03.newScale') })).toBeNull();
    expect(screen.queryByText(t('m03.inactive'))).toBeNull();
  });

  it('M-03: the scales with their stages; the Rule Administrator proposes a version', async () => {
    const calls = backend({
      'GET /v1/document-types': () => [{ code: 'OTHER', name: 'Other document', referenceRequired: false }],
      'GET /v1/pay-scales?': (url) => paged([SCALE], 1, url),
      'GET /v1/pay-scales/E-4/stages': () => [60000, 62400],
      'GET /v1/masters/circulars/current': () => [{ id: 3, number: 'HQ/PAY/2026', subject: 'Scales' }],
      'GET /v1/pay-scales/wage-revisions': () => [
        { code: 'WR-2026-01', name: 'Staff pay revision', payrollTypes: 'STAFF_IDA', status: 'DEFINED' },
      ],
      'POST /v1/pay-scales/E-4/versions': () => ({
        status: 202,
        body: { approvalId: 5, status: 'PENDING_APPROVAL', aboveMaximum: ['104882'] },
      }),
    });
    wrap(<PayScales />, me(['M-03:VIEW', 'M-03:CREATE']));
    const table = await screen.findByRole('table', { name: t('screen.M-03') });
    await userEvent.click(within(table).getByRole('button', { name: t('common.view') }));
    const grid = await screen.findByRole('list', { name: t('m03.stageGrid') });
    expect(within(grid).getAllByRole('listitem')).toHaveLength(2);
    const propose = screen.getByRole('button', { name: t('action.newVersion') });
    expect(propose).toBeDisabled();
    await userEvent.type(screen.getAllByLabelText(t('m03.min'))[0] as HTMLElement, '60000');
    await userEvent.type(screen.getAllByLabelText(t('m03.max'))[0] as HTMLElement, '70000');
    await userEvent.type(screen.getAllByLabelText(t('m03.from'))[0] as HTMLElement, '2027-01-01');
    await userEvent.selectOptions(
      screen.getByLabelText(t('m03.circular')),
      await screen.findByRole('option', { name: /HQ\/PAY\/2026/ }),
    );
    await userEvent.type(screen.getByLabelText(t('m03.stageRow', [1])), '60000');
    await userEvent.click(screen.getByRole('button', { name: t('m03.addStage') }));
    await userEvent.type(screen.getByLabelText(t('m03.stageRow', [2])), '62400');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Revision');
    // SRS M-03 Wage Revision: mandatory, chosen from the staff revisions.
    expect(propose).toBeDisabled();
    await userEvent.selectOptions(
      screen.getByLabelText(t('m03.wageRevision')),
      await screen.findByRole('option', { name: 'WR-2026-01 - Staff pay revision' }),
    );
    await userEvent.click(propose);
    expect(await screen.findByText(t('m03.proposed'))).toBeTruthy();
    expect(screen.getByText(t('m03.aboveMaximum', ['104882']))).toBeTruthy();
    expect(sent(calls, 'POST', '/versions').at(-1)).toMatchObject({
      stages: [60000, 62400],
      circularId: 3,
      wageRevision: 'WR-2026-01',
    }); // Review 17-15: the form is cleared after the proposal, so it is not proposed twice.
    expect(screen.getAllByLabelText(t('m03.min'))[0]).toHaveValue('');
    expect(screen.getByLabelText(t('m03.stageRow', [1]))).toHaveValue('');
    expect(screen.queryByLabelText(t('m03.stageRow', [2]))).toBeNull();
    expect(propose).toBeDisabled();
  });

  it('M-03: a reader sees the scales, no stages and no proposal form', async () => {
    backend({
      'GET /v1/document-types': () => [{ code: 'OTHER', name: 'Other document', referenceRequired: false }],
      'GET /v1/pay-scales?': (url) => paged([{ ...SCALE, pending: true, stages: 0 }], 1, url),
      'GET /v1/pay-scales/E-4/stages': () => [],
    });
    wrap(<PayScales />, me(['M-03:VIEW']));
    const table = await screen.findByRole('table', { name: t('screen.M-03') });
    expect(within(table).getByText(t('m03.pending'))).toBeTruthy();
    // SRS-20-29 (US-03-07 AC1): every scale opens its change history, for a reader too.
    expect(within(table).getByRole('button', { name: t('history.for', ['E-4 - IDA E-4']) })).toBeTruthy();
    await userEvent.click(within(table).getByRole('button', { name: t('common.view') }));
    expect(await screen.findByText(t('m03.noStages'))).toBeTruthy();
    expect(screen.queryByRole('button', { name: t('action.newVersion') })).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: t('common.close') }));
  });

  it('M-03 variants: a scale without a version, a failed proposal, a stagnation amount', async () => {
    backend({
      'GET /v1/document-types': () => [{ code: 'OTHER', name: 'Other document', referenceRequired: false }],
      'GET /v1/pay-scales?': (url) =>
        paged(
          [{ ...SCALE, min: null, max: null, stagnationAmount: null, effectiveFrom: null, scaleType: null }],
          1,
          url,
        ),
      'GET /v1/pay-scales/E-4/stages': () => [60000],
      'GET /v1/masters/circulars/current': () => [{ id: 3, number: 'HQ/PAY/2026', subject: null }],
      'GET /v1/pay-scales/wage-revisions': () => [
        { code: 'WR-2026-01', name: 'Staff pay revision', payrollTypes: 'STAFF_IDA', status: 'DEFINED' },
      ],
      'POST /v1/pay-scales/E-4/versions': () =>
        problem(422, 'INC-422-ENTRY', ['a version must start after every existing one']),
    });
    wrap(<PayScales />, me(['M-03:VIEW', 'M-03:CREATE']));
    const table = await screen.findByRole('table', { name: t('screen.M-03') });
    expect(within(table).getAllByText('—').length).toBe(4);
    await userEvent.click(within(table).getByRole('button', { name: t('common.view') }));
    await screen.findByRole('list', { name: t('m03.stageGrid') });
    await userEvent.type(screen.getAllByLabelText(t('m03.min'))[0] as HTMLElement, '60000');
    await userEvent.type(screen.getAllByLabelText(t('m03.max'))[0] as HTMLElement, '70000');
    await userEvent.type(screen.getAllByLabelText(t('m03.stagnation'))[0] as HTMLElement, '1500');
    await userEvent.type(screen.getAllByLabelText(t('m03.from'))[0] as HTMLElement, '2027-01-01');
    await userEvent.selectOptions(
      screen.getByLabelText(t('m03.circular')),
      await screen.findByRole('option', { name: 'HQ/PAY/2026' }),
    );
    await userEvent.type(screen.getByLabelText(t('m03.stageRow', [1])), '60000');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Revision');
    await userEvent.selectOptions(
      screen.getByLabelText(t('m03.wageRevision')),
      await screen.findByRole('option', { name: 'WR-2026-01 - Staff pay revision' }),
    );
    await userEvent.click(screen.getByRole('button', { name: t('action.newVersion') }));
    expect(
      await screen.findByText(t('error.INC-422-ENTRY', ['a version must start after every existing one'])),
    ).toBeTruthy();
    await userEvent.selectOptions(screen.getByLabelText(t('m03.circular')), '');
    expect(screen.getByRole('button', { name: t('action.newVersion') })).toBeDisabled();
  });
  it('an empty list says so', async () => {
    backend({ 'GET /v1/pay-scales?': (url) => paged([], 0, url) });
    wrap(<PayScales />, me(['M-03:VIEW']));
    expect(await screen.findByText(t('m03.none'))).toBeTruthy();
  });
});

describe('M-03 stage grid and versions (g4 UI-G4)', () => {
  it('SRS l.3949: each stage row is checked on its own; a bad row is named and blocks the proposal; a row is removed', () => {
    expect(stagesOfRows(['60000', ' ', '62400'])).toEqual([60000, 62400]);
    expect(stagesOfRows(['60000', '6a'])).toEqual([]);
    expect(badStageRow(['60000', '6a'])).toBe(2);
    expect(badStageRow(['60000', '', '0'])).toBe(3);
    expect(badStageRow(['60000', ''])).toBeNull();
  });

  it('SRS l.3952: the versions of a scale are listed, the superseded one with its Effective To', async () => {
    backend({
      'GET /v1/document-types': () => [],
      'GET /v1/pay-scales?': (url) =>
        paged(
          [
            {
              bandId: 3,
              code: 'E-4',
              name: 'IDA E-4',
              scaleType: 'IDA',
              min: 60000,
              max: 90000,
              stagnationAmount: null,
              effectiveFrom: '2026-04-01',
              stages: 1,
              pending: false,
              active: true,
            },
          ],
          1,
          url,
        ),
      'GET /v1/pay-scales/E-4/stages': () => [60000],
      'GET /v1/pay-scales/E-4/versions': () => [
        {
          versionNo: 2,
          min: 60000,
          max: 90000,
          stagnationAmount: null,
          effectiveFrom: '2026-04-01',
          effectiveTo: null,
        },
        {
          versionNo: 1,
          min: 50000,
          max: 80000,
          stagnationAmount: 1500,
          effectiveFrom: '2016-01-01',
          effectiveTo: '2026-03-31',
        },
      ],
    });
    wrap(<PayScales />, me(['M-03:VIEW']));
    const table = await screen.findByRole('table', { name: t('screen.M-03') });
    await userEvent.click(within(table).getByRole('button', { name: t('common.view') }));
    const versions = await screen.findByRole('table', { name: t('m03.versions') });
    expect(within(versions).getByText(date('2026-03-31'))).toBeTruthy();
    expect(within(versions).getByText(t('m03.inForce'))).toBeTruthy();
  });
});
