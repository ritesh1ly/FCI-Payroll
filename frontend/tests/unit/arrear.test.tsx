import { fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ArrearEvents } from '@/modules/arrear';
import { arrearApi } from '@/modules/arrear/api/arrearApi';
import { mayRecompute, maySubmit, month, remarkGiven } from '@/modules/arrear/business/arrearRules';
import { backend, me, paged, problem, referenceLists, sent, t, wrap } from './support/harness';

/**
 * I-08 Arrears &amp; Retrospective Processing (plan 13 phase 13a): the events with their totals,
 * an event's differentials, the Maker's submission with a mandatory remark, each action disabled
 * with its reason.
 */

afterEach(() => vi.unstubAllGlobals());

/** The drawn screen's own reads (OD-121): tiles, retro controls, event types, the selected event's periods. */
const SUMMARY = {
  openEvents: 37,
  awaitingApproval: 12,
  arrears: 68200000,
  arrearMembers: 4118,
  recoveries: 4120000,
  recoveryMembers: 318,
  statutory: null,
};
const CONTROLS = { maxRetroMonths: null, postRetirementMonths: null };
const TYPES = [
  { code: 'DA_REVISION', name: 'DA revision', category: 'RATE_REVISION' },
  { code: 'PROMOTION', name: 'Promotion / pay fixation', category: 'PAY_FIXATION' },
];
const DOC_TYPES = [
  { code: 'SANCTION_ORDER', name: 'Sanction Order', referenceRequired: true },
  { code: 'CIRCULAR', name: 'Circular', referenceRequired: true },
  { code: 'CERTIFICATE', name: 'Certificate', referenceRequired: false },
  { code: 'OTHER', name: 'Other', referenceRequired: false },
];
const I08 = {
  'GET /v1/arrear-events/summary': () => SUMMARY,
  'GET /v1/arrear-events/controls': () => CONTROLS,
  'GET /v1/arrear-events/types': () => TYPES,
  'GET /v1/arrear-events/5/periods': () => [],
  'GET /v1/arrear-events/6/periods': () => [],
  'GET /v1/arrear-events/8/periods': () => [],
  'GET /v1/arrear-events/12/periods': () => [],
  'GET /v1/arrear-events/9/periods': () => [],
  'GET /v1/arrear-events/13/periods': () => [],
  'GET /v1/arrear-events/5/documents': () => [],
  'GET /v1/arrear-events/6/documents': () => [],
  'GET /v1/arrear-events/8/documents': () => [],
  'GET /v1/arrear-events/9/documents': () => [],
  'GET /v1/arrear-events/12/documents': () => [],
  'GET /v1/arrear-events/13/documents': () => [],
  'GET /v1/document-types': () => DOC_TYPES,
  'GET /v1/reference/lists': () =>
    referenceLists({
      ORDER_COPY_DOCUMENT_TYPE: [
        ['SANCTION_ORDER', 'Sanction Order'],
        ['CIRCULAR', 'Circular'],
        ['CERTIFICATE', 'Certificate'],
        ['OTHER', 'Other'],
      ],
    }),
};

/** The Arrear / Retro Event Register table (the card titled as drawn). */
async function register(): Promise<HTMLElement> {
  const title = await screen.findByText(t('i08.registerTitle'));
  const table = (title.closest('.card') as HTMLElement).querySelector('table') as HTMLElement;
  // The card is drawn at once; its rows arrive with the page (the list is server-paginated).
  await vi.waitFor(() => {
    if (table.querySelector('.fl-state-loading')) throw new Error('still loading');
  });
  return table;
}
const VIEW = new RegExp('^' + t('common.view') + ' ');
/** A field by its drawn label, which ends with the source badge (ENT …) of the wireframe. */
const labelled = (scope: HTMLElement, key: string) =>
  within(scope).getByLabelText(new RegExp('^' + t(key).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')));

const EVENT = {
  id: 5,
  eventCode: 'DA_REVISION',
  eventName: 'DA revision',
  reference: 'DA-2026-07',
  effectiveFrom: '2026-07-01',
  effectiveTo: '2026-09-30',
  office: 'DH001 - Delhi Division',
  status: 'COMPUTED',
  postedToYymm: null,
  members: 1,
  arrears: 1200,
  recoveries: 300,
  raisedBy: 'job',
  raisedAt: '2026-10-01T10:00:00',
  source: 'RATE_CHANGE',
  singleMember: false,
  stated: false,
  beyondWindow: false,
  hqException: null,
};

const OLD = {
  ...EVENT,
  id: 12,
  reference: 'LEAVE HCA-9',
  effectiveFrom: '2020-01-01',
  effectiveTo: null,
  status: 'RAISED',
  source: 'LEAVE',
  singleMember: true,
  beyondWindow: true,
};

const ORDERS = [{ id: 41, number: 'HQ/ARR/41', subject: 'Old leave order' }];

const DETAIL = {
  eis: '104882',
  name: 'Rakesh Sharma',
  yymm: '202607',
  payCode: 111,
  component: 'Dearness Allowance',
  original: 10000,
  recomputed: 11200,
  arrear: 1200,
  status: 'CALCULATED',
};

describe('I-08 Arrears & Retrospective Processing', () => {
  it('SRS-8-21: the rules decide what the screen offers', () => {
    expect(maySubmit({ ...EVENT, status: 'COMPUTED' } as never)).toBe(true);
    expect(maySubmit({ ...EVENT, status: 'APPROVED' } as never)).toBe(false);
    expect(mayRecompute({ ...EVENT, singleMember: true, status: 'RAISED' } as never)).toBe(true);
    expect(mayRecompute({ ...EVENT, singleMember: true, status: 'APPROVED' } as never)).toBe(false);
    expect(mayRecompute(EVENT as never)).toBe(false);
    expect(remarkGiven('  ')).toBe(false);
    expect(remarkGiven('DA order')).toBe(true);
    expect(month('202607')).toBe('07/2026');
    expect(month(null)).toBe('—');
    expect(month('2026')).toBe('—');
    expect(arrearApi.registerUrl({})).toMatch(/\/v1\/arrear-events\/register$/);
    expect(arrearApi.registerUrl({ status: 'POSTED', eventCode: 'DA_REVISION' })).toMatch(
      /register\?status=POSTED&eventCode=DA_REVISION$/,
    );
  });

  it('SRS-8-14: the Maker reviews the differentials and submits with a remark', async () => {
    const calls = backend({
      ...I08,
      'GET /v1/arrear-events?': (url) => paged([EVENT], 1, url),
      'GET /v1/arrear-events/5/details?': (url) => paged([DETAIL], 1, url),
      'POST /v1/arrear-events/5/submissions': () => ({
        status: 202,
        body: { approvalId: 9, status: 'PENDING_APPROVAL' },
      }),
    });
    wrap(<ArrearEvents />, me(['I-08:VIEW', 'I-08:SUBMIT', 'I-08:EXPORT']));
    const table = await register();
    expect(within(table).getByText('DA-2026-07')).toBeTruthy();
    expect(within(table).getByText(t('i08.status.COMPUTED'))).toBeTruthy();
    expect(screen.getByRole('button', { name: t('i08.registers') })).toBeEnabled();
    await userEvent.click(within(table).getByRole('button', { name: VIEW }));
    const details = await screen.findByRole('table', { name: t('i08.details') });
    expect(within(details).getByText('111 - Dearness Allowance')).toBeTruthy();
    expect(within(details).getByText('07/2026')).toBeTruthy();
    const submit = screen.getByRole('button', { name: t('action.i08.submit') });
    expect(submit).toBeDisabled();
    expect(submit.getAttribute('title')).toBe(t('i08.why.remark'));
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'DA order');
    await userEvent.click(submit);
    expect(await screen.findByText(t('i08.submitted'))).toBeTruthy();
    expect(sent(calls, 'POST', '/submissions').at(-1)).toEqual({ remark: 'DA order' });
  });

  it('SRS-8-14: a refusal is shown; a posted event is not submitted again', async () => {
    backend({
      ...I08,
      'GET /v1/arrear-events?': (url) =>
        paged(
          [EVENT, { ...EVENT, id: 6, reference: 'DA-2026-04', status: 'POSTED', postedToYymm: '202605' }],
          2,
          url,
        ),
      'GET /v1/arrear-events/5/details?': (url) => paged([DETAIL], 1, url),
      'GET /v1/arrear-events/6/details?': (url) => paged([], 0, url),
      'POST /v1/arrear-events/5/submissions': () => problem(409, 'ARR-409-STATE', ['APPROVED', 'COMPUTED']),
    });
    wrap(<ArrearEvents />, me(['I-08:VIEW', 'I-08:SUBMIT']));
    const table = await register();
    expect(within(table).getByText(t('i08.postsInto', ['05/2026']))).toBeTruthy();
    const views = within(table).getAllByRole('button', { name: VIEW });
    await userEvent.click(views[0] as HTMLElement);
    await screen.findByRole('table', { name: t('i08.details') });
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'again');
    await userEvent.click(screen.getByRole('button', { name: t('action.i08.submit') }));
    expect(await screen.findByText(t('error.ARR-409-STATE', ['APPROVED', 'COMPUTED']))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('common.close') }));
    await userEvent.click(views[1] as HTMLElement);
    expect((await screen.findAllByText(t('i08.noDetails'))).length).toBeGreaterThan(0);
    const submit = screen.getByRole('button', { name: t('action.i08.submit') });
    expect(submit).toBeDisabled();
    expect(submit.getAttribute('title')).toBe(t('i08.why.submit'));
  });

  it('SRS-G-29 (OD-121): a viewer cannot submit; the register, drawn without a filter, asks for every event of the scope', async () => {
    const calls = backend({
      ...I08,
      'GET /v1/arrear-events?': (url) => paged([EVENT], 1, url),
      'GET /v1/arrear-events/5/details?': (url) => paged([DETAIL], 1, url),
    });
    wrap(<ArrearEvents />, me(['I-08:VIEW']));
    const table = await register();
    // Registers is drawn for every role (OD-125): without EXPORT pressing it says why and starts nothing.
    await userEvent.click(screen.getByRole('button', { name: t('i08.registers') }));
    expect(await screen.findByText(t('x05.why.export'))).toBeTruthy();
    expect(screen.queryByRole('dialog')).toBeNull();
    await userEvent.click(within(table).getByRole('button', { name: VIEW }));
    const submit = await screen.findByRole('button', { name: t('action.i08.submit') });
    expect(submit).toBeDisabled();
    expect(submit.getAttribute('title')).toBe(t('reason.makerOnly'));
    expect(screen.queryByLabelText(t('i08.status'))).toBeNull();
    const lists = calls.filter((c) => c.url.includes('/v1/arrear-events?'));
    expect(lists.length).toBeGreaterThan(0);
    expect(lists.every((c) => !c.url.includes('status='))).toBe(true);
  });
  it('SRS-8-13: the Maker computes a back-dated event again; a member not computable says why', async () => {
    const RETRO = {
      ...EVENT,
      id: 8,
      reference: 'LEAVE HCA-1',
      status: 'RAISED',
      source: 'LEAVE',
      singleMember: true,
    };
    let answer = { computed: false, reason: 'Rates not configured' };
    const calls = backend({
      ...I08,
      'GET /v1/arrear-events?': (url) => paged([RETRO], 1, url),
      'GET /v1/arrear-events/8/details?': (url) => paged([DETAIL], 1, url),
      'POST /v1/arrear-events/8/computations': () => answer,
    });
    wrap(<ArrearEvents />, me(['I-08:VIEW', 'I-08:SUBMIT']));
    const table = await register();
    expect(within(table).getByText(t('i08.source', ['LEAVE']))).toBeTruthy();
    await userEvent.click(within(table).getByRole('button', { name: VIEW }));
    await screen.findByRole('table', { name: t('i08.details') });
    const submit = screen.getByRole('button', { name: t('action.i08.submit') });
    expect(submit).toBeDisabled();
    const again = screen.getByRole('button', { name: t('action.i08.recompute') });
    await userEvent.click(again);
    expect(await screen.findByText(t('i08.notComputable', ['Rates not configured']))).toBeTruthy();
    answer = { computed: true, reason: null as never };
    await userEvent.click(again);
    expect(await screen.findByText(t('i08.computedAgain'))).toBeTruthy();
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Leave order');
    expect(submit).toBeEnabled();
    expect(sent(calls, 'POST', '/computations')).toHaveLength(2);
  });

  it('SRS-8-13: a recompute the server refuses is shown and the event stays as it was', async () => {
    backend({
      ...I08,
      'GET /v1/arrear-events?': (url) =>
        paged([{ ...EVENT, id: 8, status: 'RAISED', source: 'LEAVE', singleMember: true }], 1, url),
      'GET /v1/arrear-events/8/details?': (url) => paged([DETAIL], 1, url),
      'POST /v1/arrear-events/8/computations': () => problem(409, 'ARR-409-STATE'),
    });
    wrap(<ArrearEvents />, me(['I-08:VIEW', 'I-08:SUBMIT']));
    const table = await register();
    await userEvent.click(within(table).getByRole('button', { name: VIEW }));
    await screen.findByRole('table', { name: t('i08.details') });
    await userEvent.click(screen.getByRole('button', { name: t('action.i08.recompute') }));
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.queryByText(t('i08.computedAgain'))).toBeNull();
  });

  it('SRS-8-13: a viewer cannot compute again; a bulk event offers no recompute', async () => {
    backend({
      ...I08,
      'GET /v1/arrear-events?': (url) =>
        paged([{ ...EVENT, id: 8, status: 'APPROVED', source: 'LEAVE', singleMember: true }], 1, url),
      'GET /v1/arrear-events/8/details?': (url) => paged([DETAIL], 1, url),
    });
    wrap(<ArrearEvents />, me(['I-08:VIEW']));
    const table = await register();
    await userEvent.click(within(table).getByRole('button', { name: VIEW }));
    const again = await screen.findByRole('button', { name: t('action.i08.recompute') });
    expect(again).toBeDisabled();
    expect(again.getAttribute('title')).toBe(t('reason.makerOnly'));
  });

  it('SRS-8-13: the Maker cannot compute again an event already submitted', async () => {
    backend({
      ...I08,
      'GET /v1/arrear-events?': (url) =>
        paged([{ ...EVENT, id: 8, status: 'APPROVED', source: 'LEAVE', singleMember: true }], 1, url),
      'GET /v1/arrear-events/8/details?': (url) => paged([DETAIL], 1, url),
      'POST /v1/arrear-events/8/computations': () => problem(500, 'X', []),
    });
    wrap(<ArrearEvents />, me(['I-08:VIEW', 'I-08:SUBMIT']));
    const table = await register();
    await userEvent.click(within(table).getByRole('button', { name: VIEW }));
    const again = await screen.findByRole('button', { name: t('action.i08.recompute') });
    expect(again).toBeDisabled();
    expect(again.getAttribute('title')).toBe(t('i08.why.recompute'));
  });

  it('SRS-21-10: an event whose amounts the source module stated is never computed again', async () => {
    backend({
      ...I08,
      'GET /v1/arrear-events?': (url) =>
        paged([{ ...EVENT, id: 9, source: 'PENALTY', singleMember: true, stated: true }], 1, url),
      'GET /v1/arrear-events/9/details?': (url) =>
        paged([{ ...DETAIL, original: null, recomputed: null }], 1, url),
    });
    wrap(<ArrearEvents />, me(['I-08:VIEW', 'I-08:SUBMIT']));
    const table = await register();
    await userEvent.click(within(table).getByRole('button', { name: VIEW }));
    const again = await screen.findByRole('button', { name: t('action.i08.recompute') });
    expect(again).toBeDisabled();
    expect(again.getAttribute('title')).toBe(t('i08.why.stated'));
    expect(screen.getAllByText('—').length).toBeGreaterThanOrEqual(2);
  });

  it('SRS-8-6: an event beyond the back-date limit is held and the Maker asks Headquarters with its order', async () => {
    let answer: unknown = problem(409, 'ARR-409-STATE', ['RAISED', 'RAISED']);
    const calls = backend({
      ...I08,
      'GET /v1/arrear-events?': (url) => paged([OLD], 1, url),
      'GET /v1/arrear-events/12/details?': (url) => paged([], 0, url),
      'GET /v1/masters/circulars/current': () => ORDERS,
      'POST /v1/arrear-events/12/hq-exceptions': () => answer,
    });
    wrap(<ArrearEvents />, me(['I-08:VIEW', 'I-08:SUBMIT']));
    const table = await register();
    expect(within(table).getByText(t('i08.beyondWindow'))).toBeTruthy();
    await userEvent.click(within(table).getByRole('button', { name: VIEW }));
    const again = await screen.findByRole('button', { name: t('action.i08.recompute') });
    expect(again).toBeDisabled();
    expect(again.getAttribute('title')).toBe(t('i08.why.beyondWindow'));
    expect(screen.getByText(t('i08.beyondWindowHint'))).toBeTruthy();
    const ask = screen.getByRole('button', { name: t('action.i08.askHeadquarters') });
    expect(ask).toBeDisabled();
    expect(ask.getAttribute('title')).toBe(t('i08.why.hqExceptionInput'));
    await userEvent.selectOptions(await screen.findByLabelText(t('i08.hqException.order')), '41');
    expect(ask).toBeDisabled();
    await userEvent.type(screen.getByLabelText(t('i08.hqException.reason')), '  Order of 2020 ');
    expect(ask).toBeEnabled();
    await userEvent.click(ask);
    expect(await screen.findByRole('alert')).toBeTruthy();
    answer = { status: 202, body: { approvalId: 61, status: 'PENDING_APPROVAL' } };
    await userEvent.click(ask);
    expect(await screen.findByText(t('i08.hqException.asked'))).toBeTruthy();
    expect(sent(calls, 'POST', '/hq-exceptions').at(-1)).toEqual({ reason: 'Order of 2020', circularId: 41 });
    expect(screen.getByRole('button', { name: t('action.i08.askHeadquarters') })).toBeDisabled();
    expect(screen.getByLabelText(t('i08.hqException.order'))).toBeDisabled();
  });

  it('SRS-8-6: a pending exception is shown and not asked again; an approved one lets the Maker compute', async () => {
    backend({
      ...I08,
      'GET /v1/arrear-events?': (url) =>
        paged(
          [
            { ...OLD, hqException: 'PENDING' },
            { ...OLD, id: 13, hqException: 'APPROVED' },
          ],
          2,
          url,
        ),
      'GET /v1/arrear-events/12/details?': (url) => paged([], 0, url),
      'GET /v1/arrear-events/13/details?': (url) => paged([], 0, url),
    });
    wrap(<ArrearEvents />, me(['I-08:VIEW', 'I-08:SUBMIT']));
    const table = await register();
    expect(within(table).getByText(t('i08.hqException.PENDING'))).toBeTruthy();
    expect(within(table).getByText(t('i08.hqException.APPROVED'))).toBeTruthy();
    const views = within(table).getAllByRole('button', { name: VIEW });
    await userEvent.click(views[0] as HTMLElement);
    const ask = await screen.findByRole('button', { name: t('action.i08.askHeadquarters') });
    expect(ask).toBeDisabled();
    expect(ask.getAttribute('title')).toBe(t('i08.why.hqException'));
    await userEvent.click(screen.getByRole('button', { name: t('common.close') }));
    await userEvent.click(views[1] as HTMLElement);
    expect(await screen.findByRole('button', { name: t('action.i08.recompute') })).toBeEnabled();
  });

  it('SRS-8-6: a viewer sees the exception panel locked to the Maker', async () => {
    backend({
      ...I08,
      'GET /v1/arrear-events?': (url) => paged([OLD], 1, url),
      'GET /v1/arrear-events/12/details?': (url) => paged([], 0, url),
    });
    wrap(<ArrearEvents />, me(['I-08:VIEW']));
    const table = await register();
    await userEvent.click(within(table).getByRole('button', { name: VIEW }));
    const ask = await screen.findByRole('button', { name: t('action.i08.askHeadquarters') });
    expect(ask).toBeDisabled();
    expect(ask.getAttribute('title')).toBe(t('reason.makerOnly'));
  });
});

describe('I-08 source fields and actions (g4)', () => {
  async function open(perms: string[], event: object) {
    backend({
      ...I08,
      'GET /v1/arrear-events?': (url) => paged([event], 1, url),
      'GET /v1/arrear-events/5/details?': (url) => paged([DETAIL], 1, url),
    });
    wrap(<ArrearEvents />, me(perms));
    const table = await register();
    await userEvent.click(within(table).getByRole('button', { name: VIEW }));
    await screen.findByRole('table', { name: t('i08.details') });
  }

  it('SRS-21-9: the event shows its type, sanction reference, span, source and posting cycle', async () => {
    await open(['I-08:VIEW'], { ...EVENT, reference: 'HRMS/LV/77', source: 'LEAVE', postedToYymm: '202611' });
    expect(screen.getByText(t('i08.sanctionReference'))).toBeTruthy();
    expect(screen.getAllByText('HRMS/LV/77').length).toBeGreaterThan(0);
    expect(screen.getByText('LEAVE')).toBeTruthy();
    expect(screen.getByText('11/2026')).toBeTruthy();
  });

  it('SRS-8-15 (l.3095): a bulk event offers Recompute disabled with its reason', async () => {
    await open(['I-08:VIEW', 'I-08:SUBMIT'], EVENT);
    const again = screen.getByRole('button', { name: t('action.i08.recompute') });
    expect(again).toBeDisabled();
    expect(again.getAttribute('title')).toBe(t('i08.why.bulkRecompute'));
  });

  it('SRS-8-14: the Checker approves and merges in the Approvals Inbox; the Maker has no such link', async () => {
    await open(['I-08:VIEW', 'I-08:APPROVE'], EVENT);
    expect(screen.getByRole('link', { name: t('i08.approveInInbox') }).getAttribute('href')).toContain(
      's=W-01',
    );
    // As drawn (OD-121): Undo event (OTP) is on the register toolbar for every role; for the Checker it
    // answers whose right it is and opens nothing.
    const undo = screen.getByRole('button', { name: t('i08.undoEventAct') });
    expect(undo).toBeEnabled();
    await userEvent.click(undo);
    expect(await screen.findByText(t('i08.why.undoRight'))).toBeTruthy();
    expect(screen.queryByRole('dialog', { name: t('i08.undo.title', ['DA-2026-07']) })).toBeNull();
  });

  it('SRS-21-6: the Maker selects an approved event and asks its controlled undo with a reason, on the cycle it posts into', async () => {
    const calls = backend({
      ...I08,
      'GET /v1/arrear-events?': (url) =>
        paged([{ ...EVENT, status: 'APPROVED', postedToYymm: '202611' }], 1, url),
      'GET /v1/arrear-events/5/details?': (url) => paged([DETAIL], 1, url),
      'POST /v1/cycles/2026-11/undo-requests': () => ({ status: 202, body: { approvalId: 77 } }),
    });
    wrap(<ArrearEvents />, me(['I-08:VIEW', 'I-08:SUBMIT', 'C-02:UNDO']));
    const table = await register();
    const undo = screen.getByRole('button', { name: t('i08.undoEventAct') });
    expect(undo).toBeEnabled();
    // The first event of the register is selected on load (the period table shows it).
    expect(within(table).getByText('DA-2026-07').closest('tr')).toHaveAttribute('aria-selected', 'true');
    await userEvent.click(undo);
    const dialog = screen.getByRole('dialog', { name: t('i08.undo.title', ['DA-2026-07']) });
    expect(within(dialog).getByText(t('i08.undo.note'))).toBeTruthy();
    const confirm = within(dialog).getByRole('button', { name: t('action.i08.undo') });
    expect(confirm).toBeDisabled();
    await userEvent.type(within(dialog).getByLabelText(t('common.reason')), '  Wrong DA order  ');
    await userEvent.click(confirm);
    expect(await screen.findByText(t('i08.undoRequested', [77]))).toBeTruthy();
    expect(sent(calls, 'POST', '/undo-requests')).toEqual([
      { transactionEntity: 'arrear_event', transactionKey: '5', reason: 'Wrong DA order' },
    ]);
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('SRS-21-6: a refused undo keeps the dialog and the reason; Cancel closes it with nothing sent again', async () => {
    const calls = backend({
      ...I08,
      'GET /v1/arrear-events?': (url) =>
        paged([{ ...EVENT, status: 'APPROVED', postedToYymm: '202611' }], 1, url),
      'GET /v1/arrear-events/5/details?': (url) => paged([DETAIL], 1, url),
      'POST /v1/cycles/2026-11/undo-requests': () => problem(409, 'CYC-409-POSTED'),
    });
    wrap(<ArrearEvents />, me(['I-08:VIEW', 'I-08:SUBMIT', 'C-02:UNDO']));
    const table = await register();
    await userEvent.click(within(table).getByText('DA-2026-07'));
    await userEvent.click(screen.getByRole('button', { name: t('i08.undoEventAct') }));
    const dialog = screen.getByRole('dialog');
    await userEvent.type(within(dialog).getByLabelText(t('common.reason')), 'Posted meanwhile');
    await userEvent.click(within(dialog).getByRole('button', { name: t('action.i08.undo') }));
    expect(await within(dialog).findByText(t('error.CYC-409-POSTED'))).toBeTruthy();
    expect(within(dialog).getByLabelText(t('common.reason'))).toHaveValue('Posted meanwhile');
    await userEvent.click(within(dialog).getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(sent(calls, 'POST', '/undo-requests')).toHaveLength(1);
  });

  it('SRS-21-6: an event not approved, or a posted one, is not undone: the drawn button answers why; a posted event is corrected by a new arrear', async () => {
    backend({
      ...I08,
      'GET /v1/arrear-events?': (url) =>
        paged(
          [EVENT, { ...EVENT, id: 6, reference: 'DA-2026-04', status: 'POSTED', postedToYymm: '202610' }],
          2,
          url,
        ),
    });
    wrap(<ArrearEvents />, me(['I-08:VIEW', 'I-08:SUBMIT', 'C-02:UNDO']));
    const table = await register();
    await userEvent.click(within(table).getByText('DA-2026-07'));
    await userEvent.click(screen.getByRole('button', { name: t('i08.undoEventAct') }));
    expect(await screen.findByText(t('i08.why.undo'))).toBeTruthy();
    expect(screen.queryByRole('dialog')).toBeNull();
    await userEvent.click(within(table).getByText('DA-2026-04'));
    await userEvent.click(screen.getByRole('button', { name: t('i08.undoEventAct') }));
    expect((await screen.findAllByText(t('i08.why.undo'))).length).toBeGreaterThan(0);
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('SRS-21-6: a Maker without the cycle undo right is told why and no undo is asked', async () => {
    backend({
      ...I08,
      'GET /v1/arrear-events?': (url) =>
        paged([{ ...EVENT, status: 'APPROVED', postedToYymm: '202611' }], 1, url),
    });
    wrap(<ArrearEvents />, me(['I-08:VIEW', 'I-08:SUBMIT']));
    await register();
    const noRight = screen.getByRole('button', { name: t('i08.undoEventAct') });
    expect(noRight).toBeEnabled();
    await userEvent.click(noRight);
    expect(await screen.findByText(t('i08.why.undoRight'))).toBeTruthy();
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('SRS-21-6 / SRS-8-13: with an empty register Undo event and Recompute ask to select an event first', async () => {
    const calls = backend({ ...I08, 'GET /v1/arrear-events?': (url) => paged([], 0, url) });
    wrap(<ArrearEvents />, me(['I-08:VIEW', 'I-08:SUBMIT', 'C-02:UNDO']));
    expect(await screen.findByText(t('i08.none'))).toBeTruthy();
    // No event: the period table says so, Member view is drawn disabled, the order card is view only.
    expect(screen.getByText(t('i08.periodsNone'))).toBeTruthy();
    expect(screen.getByRole('button', { name: t('i08.memberView') })).toBeDisabled();
    expect(screen.getByText(t('i08.ord.hint'))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('i08.undoEventAct') }));
    expect(await screen.findByText(t('i08.selectEvent'))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('i08.recomputeAct') }));
    expect((await screen.findAllByText(t('i08.selectEvent'))).length).toBeGreaterThan(0);
    expect(sent(calls, 'POST', '/undo-requests')).toHaveLength(0);
    expect(sent(calls, 'POST', '/computations')).toHaveLength(0);
  });

  it('SRS-8-12: the four worked scenarios are the drawn UAT reference table', async () => {
    backend({ ...I08, 'GET /v1/arrear-events?': (url) => paged([], 0, url) });
    wrap(<ArrearEvents />, me(['I-08:VIEW']));
    const title = await screen.findByText(t('i08.scenarios'));
    const table = (title.closest('.card') as HTMLElement).querySelector('table') as HTMLElement;
    expect(within(table).getAllByRole('row')).toHaveLength(5);
    expect(within(table).getByText(t('i08.scn.1.event'))).toBeTruthy();
    expect(within(table).getByText(t('i08.scn.4.statutory'))).toBeTruthy();
  });

  it('SRS-G-54 (OD-121): the tiles, the retro controls and the period-wise differential of the selected event are drawn', async () => {
    const PERIODS = [
      {
        yymm: '202604',
        original: 24108400,
        revised: 25822900,
        basicDiff: 1140200,
        daDiff: 484100,
        hraDiff: 90200,
        netArrear: 1714500,
      },
      {
        yymm: '202605',
        original: 24108400,
        revised: 25822900,
        basicDiff: 1140200,
        daDiff: 484100,
        hraDiff: 90200,
        netArrear: 1714500,
      },
    ];
    backend({
      ...I08,
      'GET /v1/arrear-events/controls': () => ({ maxRetroMonths: 60, postRetirementMonths: null }),
      'GET /v1/arrear-events/5/periods': () => PERIODS,
      'GET /v1/arrear-events?': (url) => paged([EVENT], 1, url),
      'GET /v1/arrear-events/5/details?': (url) => paged([DETAIL], 1, url),
    });
    wrap(<ArrearEvents />, me(['I-08:VIEW']));
    const table = await register();
    const tile = (label: string) => screen.getByText(label).closest('.tile') as HTMLElement;
    expect(within(tile(t('i08.tile.open'))).getByText('37')).toBeTruthy();
    expect(within(tile(t('i08.tile.open'))).getByText(t('i08.tile.openSub', [12]))).toBeTruthy();
    expect(within(tile(t('i08.tile.arrears'))).getByText(t('i08.tile.members', [4118]))).toBeTruthy();
    expect(within(tile(t('i08.tile.recoveries'))).getByText(t('i08.tile.members', [318]))).toBeTruthy();
    // Statutory on arrears is computed in the next cycle run: never a zero.
    expect(within(tile(t('i08.tile.statutory'))).getByText(t('i08.unavailable.NEXT_RUN'))).toBeTruthy();
    // Retro controls: the configured window reads its value, the unconfigured one says so (R-RULE).
    expect(await screen.findByText(t('i08.ctl.windowValue', [60]))).toBeTruthy();
    expect(screen.getByText(t('i08.ctl.notConfigured'))).toBeTruthy();
    expect(screen.getByText(t('i08.ctl.crossFyValue'))).toBeTruthy();
    // The first event is selected on load: its period-wise differential is shown, Member view is open.
    expect(await screen.findByText(t('i08.periodsTitle', ['DA-2026-07']))).toBeTruthy();
    expect(screen.getByRole('button', { name: t('i08.memberView') })).toBeEnabled();
    // Clicking the selected row keeps it selected (selection, not a toggle).
    await userEvent.click(within(table).getByText('DA-2026-07'));
    expect(screen.getByText(t('i08.periodsTitle', ['DA-2026-07']))).toBeTruthy();
    expect(await screen.findByText('202604')).toBeTruthy();
    expect(screen.getByText(t('i08.periodsTotal', [2]))).toBeTruthy();
    expect(screen.getAllByText('34,29,000').length).toBeGreaterThan(0);
    // Member view opens the event's differentials.
    await userEvent.click(screen.getByRole('button', { name: t('i08.memberView') }));
    expect(await screen.findByRole('table', { name: t('i08.details') })).toBeTruthy();
  });

  it('SRS-8-8: the Maker raises a new retro event from the database event types; a viewer sees the action drawn disabled', async () => {
    const calls = backend({
      ...I08,
      'GET /v1/arrear-events?': (url) => paged([EVENT], 1, url),
      'GET /v1/masters/circulars/current': () => ORDERS,
      'POST /v1/arrear-events': () => ({ status: 201, body: { id: 91 } }),
    });
    wrap(<ArrearEvents />, me(['I-08:VIEW', 'I-08:SUBMIT']));
    await register();
    await userEvent.click(screen.getByRole('button', { name: t('i08.newEvent') }));
    const dialog = screen.getByRole('dialog', { name: t('i08.new.title') });
    const raise = within(dialog).getByRole('button', { name: t('i08.new.raise') });
    expect(raise).toBeDisabled();
    const type = labelled(dialog, 'i08.new.type');
    expect(
      within(type)
        .getAllByRole('option')
        .map((o) => o.textContent),
    ).toEqual([t('common.choose'), 'DA_REVISION - DA revision', 'PROMOTION - Promotion / pay fixation']);
    await userEvent.selectOptions(type, 'PROMOTION');
    await userEvent.type(labelled(dialog, 'i08.new.reference'), 'Promotion batch E-3 to E-4');
    await userEvent.selectOptions(labelled(dialog, 'i08.new.payrollType'), 'STAFF');
    fireEvent.change(labelled(dialog, 'i08.new.from'), { target: { value: '2026-04-01' } });
    fireEvent.change(labelled(dialog, 'i08.new.to'), { target: { value: '2026-06-30' } });
    expect(raise).toBeEnabled();
    await userEvent.click(raise);
    expect(await screen.findByText(t('i08.new.raised', [91]))).toBeTruthy();
    expect(sent(calls, 'POST', '/v1/arrear-events')).toEqual([
      {
        eventCode: 'PROMOTION',
        reference: 'Promotion batch E-3 to E-4',
        payrollType: 'STAFF',
        effectiveFrom: '2026-04-01',
        effectiveTo: '2026-06-30',
        circularId: null,
      },
    ]);
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('SRS-G-29: a viewer sees New Retro Event, Recompute and Approve & Merge drawn disabled with their reasons; the Checker gets Approve & Merge', async () => {
    backend({ ...I08, 'GET /v1/arrear-events?': (url) => paged([EVENT], 1, url) });
    const viewer = wrap(<ArrearEvents />, me(['I-08:VIEW']));
    await register();
    for (const [label, why] of [
      [t('i08.newEvent'), t('i08.why.newEvent')],
      [t('i08.recomputeAct'), t('reason.makerOnly')],
      [t('i08.approveMerge'), t('i08.why.approveMerge')],
    ]) {
      const button = screen.getByRole('button', { name: label });
      expect(button).toBeDisabled();
      expect(button.getAttribute('data-why')).toBe(why);
    }
    viewer.unmount();
    backend({ ...I08, 'GET /v1/arrear-events?': (url) => paged([EVENT], 1, url) });
    wrap(<ArrearEvents />, me(['I-08:VIEW', 'I-08:APPROVE']));
    await register();
    expect(screen.getByRole('link', { name: t('i08.approveMerge') }).getAttribute('href')).toContain(
      's=W-01',
    );
  });

  it('SRS-8-13: Recompute over the register needs a selected single-member event; a bulk one says why', async () => {
    const calls = backend({
      ...I08,
      'GET /v1/arrear-events?': (url) =>
        paged(
          [EVENT, { ...EVENT, id: 8, reference: 'LEAVE HCA-1', status: 'RAISED', singleMember: true }],
          2,
          url,
        ),
      'POST /v1/arrear-events/8/computations': () => ({ computed: true, reason: null }),
    });
    wrap(<ArrearEvents />, me(['I-08:VIEW', 'I-08:SUBMIT']));
    const table = await register();
    const again = screen.getByRole('button', { name: t('i08.recomputeAct') });
    // The first event (a bulk one) is selected on load: Recompute says why it does not apply.
    await userEvent.click(again);
    expect(await screen.findByText(t('i08.why.bulkRecompute'))).toBeTruthy();
    expect(sent(calls, 'POST', '/computations')).toHaveLength(0);
    await userEvent.click(within(table).getByText('LEAVE HCA-1'));
    await userEvent.click(again);
    expect(await screen.findByText(t('i08.computedAgain'))).toBeTruthy();
    expect(sent(calls, 'POST', '/computations')).toHaveLength(1);
  });
});

describe('I-08 drawn row actions and order copy (OD-121)', () => {
  it('SRS-G-54: the Checker gets ✔ on every row of the register, which opens the Approvals Inbox', async () => {
    backend({
      ...I08,
      'GET /v1/arrear-events?': (url) =>
        paged([EVENT, { ...EVENT, id: 6, reference: 'DA-2026-04', status: 'POSTED' }], 2, url),
    });
    wrap(<ArrearEvents />, me(['I-08:VIEW', 'I-08:APPROVE']));
    const table = await register();
    expect(within(table).getAllByRole('button', { name: '✔' })).toHaveLength(2);
    const viewer = within(table).getAllByRole('button', { name: VIEW });
    expect(viewer).toHaveLength(2);
  });

  it('SRS-G-54: a viewer has no ✔ and sees the order card view only, with the stored order and its link', async () => {
    backend({
      ...I08,
      'GET /v1/arrear-events?': (url) => paged([EVENT], 1, url),
      'GET /v1/arrear-events/5/documents': () => [
        {
          id: 31,
          fileName: 'da_order.pdf',
          documentTypeCode: 'CIRCULAR',
          documentTypeName: 'Circular',
          referenceNumber: 'FCI/EST/2026/118',
          uploadedBy: 'maker.delhi@fci-demo.test',
          uploadedAt: '2026-07-12T05:34:00Z',
        },
      ],
    });
    wrap(<ArrearEvents />, me(['I-08:VIEW']));
    const table = await register();
    expect(within(table).queryByRole('button', { name: '✔' })).toBeNull();
    const card = (await screen.findByText(t('wf.ord.title'))).closest('.card') as HTMLElement;
    expect(within(card).getByText(t('wf.ord.viewOnly'))).toBeTruthy();
    expect(within(card).getByText(t('i08.ord.hint'))).toBeTruthy();
    for (const input of card.querySelectorAll('input, select')) expect(input).toBeDisabled();
    expect(await within(card).findByText('da_order.pdf')).toBeTruthy();
    expect(
      within(card)
        .getByRole('link', { name: t('wf.view') })
        .getAttribute('href'),
    ).toContain('/v1/arrear-events/5/documents/31');
    expect(
      within(card)
        .getByRole('link', { name: t('wf.ord.viewOrder') })
        .getAttribute('href'),
    ).toContain('/documents/31');
  });

  it('SRS-G-30: the Maker picks the files after the particulars and each file is stored with them', async () => {
    const calls = backend({
      ...I08,
      'GET /v1/arrear-events?': (url) => paged([{ ...EVENT, status: 'RAISED' }], 1, url),
      'POST /v1/arrear-events/5/documents': () => ({ status: 201, body: { id: 40 } }),
    });
    wrap(<ArrearEvents />, me(['I-08:VIEW', 'I-08:SUBMIT']));
    await register();
    const card = (await screen.findByText(t('wf.ord.title'))).closest('.card') as HTMLElement;
    // No order yet: View order is drawn enabled (as the pack draws it) and says that none is stored; the route
    // needs none, so nothing is "blocked".
    await userEvent.click(within(card).getByRole('button', { name: t('wf.ord.viewOrder') }));
    expect((await screen.findAllByText(t('ord.none'))).length).toBeGreaterThan(0);
    expect(within(card).getByText(t('wf.ord.noneYet'))).toBeTruthy();
    const [type] = Array.from(card.querySelectorAll('select')) as HTMLSelectElement[];
    const [reference, orderDate, office, files] = Array.from(
      card.querySelectorAll('input'),
    ) as HTMLInputElement[];
    const pdf = (name: string) => new File(['%PDF-1.7'], name, { type: 'application/pdf' });
    // A circular needs its reference: picking files before it stores nothing and says what is missing.
    await userEvent.selectOptions(type as HTMLSelectElement, 'CIRCULAR');
    fireEvent.change(orderDate as HTMLInputElement, { target: { value: '2026-07-01' } });
    await userEvent.upload(files as HTMLInputElement, pdf('a.pdf'));
    expect(await within(card).findByText(t('wf.ord.particularsFirst'))).toBeTruthy();
    expect(sent(calls, 'POST', '/documents')).toHaveLength(0);
    // With the reference, two files are stored one by one with the same particulars.
    await userEvent.type(reference as HTMLInputElement, 'FCI/EST/2026/118');
    await userEvent.type(office as HTMLInputElement, 'RO Delhi');
    await userEvent.upload(files as HTMLInputElement, [pdf('a.pdf'), pdf('b.pdf')]);
    expect(await within(card).findByText(t('wf.ord.stored', [2]))).toBeTruthy();
    expect(
      calls.filter((c) => c.method === 'POST' && c.url.endsWith('/v1/arrear-events/5/documents')),
    ).toHaveLength(2);
  });

  it('SRS-G-30: a future order date and a refused upload store nothing; an approved event takes no order', async () => {
    backend({
      ...I08,
      'GET /v1/arrear-events?': (url) =>
        paged(
          [
            { ...EVENT, status: 'RAISED' },
            { ...EVENT, id: 6, reference: 'DA-2026-04', status: 'APPROVED' },
          ],
          2,
          url,
        ),
      'POST /v1/arrear-events/5/documents': () => problem(422, 'DOC-422-TYPE'),
    });
    wrap(<ArrearEvents />, me(['I-08:VIEW', 'I-08:SUBMIT']));
    const table = await register();
    let card = (await screen.findByText(t('wf.ord.title'))).closest('.card') as HTMLElement;
    const [type] = Array.from(card.querySelectorAll('select')) as HTMLSelectElement[];
    const inputs = () => Array.from(card.querySelectorAll('input')) as HTMLInputElement[];
    await userEvent.selectOptions(type as HTMLSelectElement, 'OTHER');
    fireEvent.change(inputs()[1] as HTMLInputElement, { target: { value: '2999-01-01' } });
    await userEvent.upload(
      inputs()[3] as HTMLInputElement,
      new File(['x'], 'x.pdf', { type: 'application/pdf' }),
    );
    expect(await within(card).findByText(t('wf.ord.particularsFirst'))).toBeTruthy();
    fireEvent.change(inputs()[1] as HTMLInputElement, { target: { value: '2026-07-01' } });
    await userEvent.upload(
      inputs()[3] as HTMLInputElement,
      new File(['x'], 'x.pdf', { type: 'application/pdf' }),
    );
    expect(await within(card).findByRole('alert')).toBeTruthy();
    // The approved event: the card is view only.
    await userEvent.click(within(table).getByText('DA-2026-04'));
    card = (await screen.findByText(t('wf.ord.title'))).closest('.card') as HTMLElement;
    expect(within(card).getByText(t('wf.ord.viewOnly'))).toBeTruthy();
  });
});

describe('I-08 card follows the list (g4 UI-G4)', () => {
  it('Review 13-9 (SRS-8-14): a status changed elsewhere reaches the open card, which no longer offers Submit', async () => {
    let status = 'COMPUTED';
    backend({
      ...I08,
      'GET /v1/arrear-events?': (url) => paged([{ ...EVENT, status }], 1, url),
      'GET /v1/arrear-events/5/details?': (url) => paged([DETAIL], 1, url),
    });
    wrap(<ArrearEvents />, me(['I-08:VIEW', 'I-08:SUBMIT']));
    const table = await register();
    await userEvent.click(within(table).getByRole('button', { name: VIEW }));
    await userEvent.type(await screen.findByLabelText(t('common.reason')), 'DA order');
    expect(screen.getByRole('button', { name: t('action.i08.submit') })).toBeEnabled();
    status = 'APPROVED';
    // The register is read again (here: another page size); the open card follows the fresh row.
    await userEvent.selectOptions(screen.getAllByLabelText(t('page.size'))[0] as HTMLElement, '50');
    await vi.waitFor(() =>
      expect(screen.getByRole('button', { name: t('action.i08.submit') })).toBeDisabled(),
    );
  });
});
