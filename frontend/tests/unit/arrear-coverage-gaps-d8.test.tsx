import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ArrearEvents } from '@/modules/arrear';
import { amount } from '@/shared/ui';
import { backend, me, paged, problem, referenceLists, sent, t, wrap } from './support/harness';

/**
 * I-08 Arrears & Retrospective Events as Wireframes v6.1 draws it (OD-121; SRS-8-6, SRS-8-8, SRS-8-13, SRS-8-14):
 * the statutory tile and the post-retirement window once configured, the pill of each status, the Checker's ✔ sent to
 * W-01, Recompute refused for an event that may not be recomputed or that fails, and the New Retro Event dialog with
 * its optional end date and order, its body sent as typed and a refusal shown in the dialog.
 */
afterEach(() => vi.unstubAllGlobals());

const MAKER = me(['I-08:VIEW', 'I-08:SUBMIT']);
const CHECKER = me(['I-08:VIEW', 'I-08:APPROVE'], { roles: ['PAYROLL_CHECKER'] });

const EVENT = {
  id: 8,
  eventCode: 'DA_REVISION',
  eventName: 'DA revision',
  reference: 'DA-2026-07',
  effectiveFrom: '2026-07-01',
  effectiveTo: '2026-09-30',
  office: null,
  status: 'APPROVED',
  postedToYymm: null,
  members: 1,
  arrears: 1200,
  recoveries: 0,
  raisedBy: 'job',
  raisedAt: '2026-10-01T10:00:00',
  source: null,
  singleMember: true,
  stated: true,
  beyondWindow: false,
  hqException: null,
};
const REJECTED = { ...EVENT, id: 9, reference: 'DA-2026-08', status: 'REVERSED', singleMember: false };
const RAISED = { ...EVENT, id: 10, reference: 'DA-2026-09', status: 'RAISED', stated: false };

function routes(events: unknown[], over: Record<string, (url: string, init?: RequestInit) => unknown> = {}) {
  return backend({
    'GET /v1/arrear-events/summary': () => ({
      openEvents: 1,
      awaitingApproval: 0,
      arrears: 1200,
      arrearMembers: 1,
      recoveries: 0,
      recoveryMembers: 0,
      statutory: 3456,
    }),
    'GET /v1/arrear-events/controls': () => ({ maxRetroMonths: 24, postRetirementMonths: 6 }),
    'GET /v1/arrear-events/types': () => [
      { code: 'DA_REVISION', name: 'DA revision', category: 'RATE_REVISION' },
    ],
    'GET /v1/arrear-events/8/periods': () => [],
    'GET /v1/arrear-events/9/periods': () => [],
    'GET /v1/arrear-events/10/periods': () => [],
    'GET /v1/masters/circulars/current': () => [{ id: 41, number: 'HQ/ARR/41', subject: 'DA order' }],
    'GET /v1/arrear-events?': (url: string) => paged(events, events.length, url),
    ...over,
  });
}

async function register(): Promise<HTMLElement> {
  const title = await screen.findByText(t('i08.registerTitle'));
  const table = (title.closest('.card') as HTMLElement).querySelector('table') as HTMLElement;
  await within(table).findByText('DA-2026-07');
  return table;
}

describe('I-08 tiles, rows and toolbar', () => {
  it('SRS_8_14_theStatutoryTileAndThePostRetirementWindowShowOnceConfigured', async () => {
    routes([EVENT, REJECTED]);
    wrap(<ArrearEvents />, MAKER);
    const table = await register();
    expect(await screen.findByText(amount(3456))).toBeInTheDocument();
    expect(screen.getByText(t('i08.ctl.windowValue', [6]))).toBeInTheDocument();
    expect(screen.getByText(t('i08.ctl.windowValue', [24]))).toBeInTheDocument();
    expect(within(table).getByText(t('i08.status.APPROVED'))).toHaveClass('p-ok');
    expect(within(table).getByText(t('i08.status.REVERSED'))).toHaveClass('p-gray');
    // an end date is shown, never a dash
    const row = within(table).getByText('DA-2026-07').closest('tr') as HTMLElement;
    expect(within(row).queryByText('—')).toBeNull();
  });

  it('SRS_8_13_theCheckersTickGoesToW01', async () => {
    routes([EVENT]);
    const assign = vi.fn();
    vi.stubGlobal('location', { ...window.location, assign });
    const user = userEvent.setup();
    wrap(<ArrearEvents />, CHECKER);
    const table = await register();
    await user.click(within(table).getByTitle(t('wf.approveTitle')));
    expect(assign).toHaveBeenCalledWith(expect.stringContaining('s=W-01'));
  });

  it('SRS_8_13_aStatedEventIsNotRecomputedAndABulkOneIsToldWhy', async () => {
    const calls = routes([EVENT, REJECTED]);
    const user = userEvent.setup();
    wrap(<ArrearEvents />, MAKER);
    const table = await register();
    // the first row is selected on load: stated, so not recomputable
    await user.click(screen.getByRole('button', { name: t('i08.recomputeAct') }));
    expect(await screen.findByText(t('i08.why.recompute'))).toBeInTheDocument();
    await user.click(within(table).getByText('DA-2026-08'));
    await user.click(screen.getByRole('button', { name: t('i08.recomputeAct') }));
    expect(await screen.findByText(t('i08.why.bulkRecompute'))).toBeInTheDocument();
    expect(sent(calls, 'POST', '/computations')).toHaveLength(0);
  });

  it('SRS_8_13_aRecomputeThatFailsSaysSoAndOneThatSucceedsSaysComputedAgain', async () => {
    let fail = true;
    const calls = routes([{ ...RAISED, reference: 'DA-2026-07' }], {
      'POST /v1/arrear-events/10/computations': () =>
        fail ? problem(500, 'SYS-500') : { computed: true, reason: null },
    });
    const user = userEvent.setup();
    wrap(<ArrearEvents />, MAKER);
    await register();
    await user.click(screen.getByRole('button', { name: t('i08.recomputeAct') }));
    expect(await screen.findByText(t('error.unexpected'))).toBeInTheDocument();
    fail = false;
    await user.click(screen.getByRole('button', { name: t('i08.recomputeAct') }));
    expect(await screen.findByText(t('i08.computedAgain'))).toBeInTheDocument();
    expect(sent(calls, 'POST', '/v1/arrear-events/10/computations')).toHaveLength(2);
  });
});

describe('I-08 New Retro Event', () => {
  async function openDialog() {
    await userEvent.click(await screen.findByRole('button', { name: t('i08.newEvent') }));
    return screen.getByRole('dialog', { name: t('i08.new.title') });
  }

  it('SRS_8_8_theEventIsRaisedWithItsEndDateOrderAndPayrollTypeAsTyped', async () => {
    const calls = routes([EVENT], { 'POST /v1/arrear-events': () => ({ status: 201, body: { id: 77 } }) });
    const user = userEvent.setup();
    wrap(<ArrearEvents />, MAKER);
    await register();
    const dialog = await openDialog();
    const [type, payroll, order] = Array.from(dialog.querySelectorAll('select')) as [
      HTMLSelectElement,
      HTMLSelectElement,
      HTMLSelectElement,
    ];
    const [reference, from, to] = Array.from(dialog.querySelectorAll('input')) as [
      HTMLInputElement,
      HTMLInputElement,
      HTMLInputElement,
    ];
    await within(dialog).findByRole('option', { name: 'DA_REVISION - DA revision' });
    await user.selectOptions(type, 'DA_REVISION');
    await user.type(reference, '  DA order 41  ');
    fireEvent.change(from, { target: { value: '2026-07-01' } });
    fireEvent.change(to, { target: { value: '2026-09-30' } });
    // an end date cleared again is sent as none
    fireEvent.change(to, { target: { value: '' } });
    fireEvent.change(to, { target: { value: '2026-08-31' } });
    expect(within(dialog).getByRole('option', { name: 'HQ/ARR/41 - DA order' })).toBeInTheDocument();
    await user.selectOptions(order, '41');
    await user.selectOptions(payroll, (payroll.options[1] as HTMLOptionElement).value);
    const chosenType = (payroll.options[1] as HTMLOptionElement).value;
    await user.click(within(dialog).getByRole('button', { name: t('i08.new.raise') }));
    expect(await screen.findByText(t('i08.new.raised', [77]))).toBeInTheDocument();
    expect(sent(calls, 'POST', '/v1/arrear-events')).toEqual([
      {
        eventCode: 'DA_REVISION',
        reference: 'DA order 41',
        payrollType: chosenType,
        effectiveFrom: '2026-07-01',
        effectiveTo: '2026-08-31',
        circularId: 41,
      },
    ]);
    expect(screen.queryByRole('dialog', { name: t('i08.new.title') })).toBeNull();
  });

  it('SRS_8_8_anOrderUnchosenAndAnEmptyEndAreSentAsNoneARefusalStaysInTheDialog', async () => {
    const calls = routes([EVENT], { 'POST /v1/arrear-events': () => problem(409, 'ARR-409-OVERLAP') });
    const user = userEvent.setup();
    wrap(<ArrearEvents />, MAKER);
    await register();
    const dialog = await openDialog();
    const [type, , order] = Array.from(dialog.querySelectorAll('select')) as [
      HTMLSelectElement,
      HTMLSelectElement,
      HTMLSelectElement,
    ];
    const [reference, from, to] = Array.from(dialog.querySelectorAll('input')) as [
      HTMLInputElement,
      HTMLInputElement,
      HTMLInputElement,
    ];
    await within(dialog).findByRole('option', { name: 'DA_REVISION - DA revision' });
    await user.selectOptions(type, 'DA_REVISION');
    await user.type(reference, 'DA 2026');
    fireEvent.change(from, { target: { value: '2026-07-01' } });
    fireEvent.change(to, { target: { value: '' } });
    await user.selectOptions(order, '41');
    await user.selectOptions(order, '');
    // Enter in a field submits the form
    fireEvent.submit(dialog);
    await waitFor(() => expect(sent(calls, 'POST', '/v1/arrear-events')).toHaveLength(1));
    expect(sent(calls, 'POST', '/v1/arrear-events')[0]).toMatchObject({
      effectiveTo: null,
      circularId: null,
    });
    expect(await within(dialog).findByRole('alert')).toBeInTheDocument();
    expect(screen.getByRole('dialog', { name: t('i08.new.title') })).toBeInTheDocument();
  });

  it('SRS_8_8_submittingAnIncompleteFormSendsNothing', async () => {
    const calls = routes([EVENT]);
    wrap(<ArrearEvents />, MAKER);
    await register();
    const dialog = await openDialog();
    fireEvent.submit(dialog);
    expect(sent(calls, 'POST', '/v1/arrear-events')).toHaveLength(0);
  });
});

describe('I-08 refusal reasons and orders without subject', () => {
  it('SRS_8_13_aRecomputeRefusedWithAReasonNamesIt', async () => {
    routes([{ ...RAISED, reference: 'DA-2026-07' }], {
      'POST /v1/arrear-events/10/computations': () => ({ computed: false, reason: 'Member retired' }),
    });
    const user = userEvent.setup();
    wrap(<ArrearEvents />, me(['I-08:VIEW', 'I-08:SUBMIT'], { period: undefined as never }));
    await register();
    await user.click(screen.getByRole('button', { name: t('i08.recomputeAct') }));
    expect(await screen.findByText(t('i08.notComputable', ['Member retired']))).toBeInTheDocument();
  });

  it('SRS_8_8_anOrderWithoutSubjectIsOfferedByItsNumberAlone', async () => {
    routes([EVENT], {
      'GET /v1/masters/circulars/current': () => [{ id: 42, number: 'HQ/ARR/42', subject: null }],
    });
    wrap(<ArrearEvents />, MAKER);
    await register();
    await userEvent.click(await screen.findByRole('button', { name: t('i08.newEvent') }));
    const dialog = screen.getByRole('dialog', { name: t('i08.new.title') });
    expect(await within(dialog).findByRole('option', { name: 'HQ/ARR/42' })).toBeInTheDocument();
  });
});

describe('I-08 order copy of an unlisted type', () => {
  it('SRS_G_30_aTypeTheDocumentTypesDoNotListNeedsNoReference', async () => {
    const calls = routes([{ ...RAISED, reference: 'DA-2026-07' }], {
      'GET /v1/arrear-events/10/documents': () => [],
      'GET /v1/document-types': () => [{ code: 'CIRCULAR', name: 'Circular', referenceRequired: true }],
      'GET /v1/reference/lists': () =>
        referenceLists({ ORDER_COPY_DOCUMENT_TYPE: [['ARREAR_ORDER', 'Arrear order']] }),
      'POST /v1/arrear-events/10/documents': () => ({ status: 201, body: [] }),
    });
    const user = userEvent.setup();
    wrap(<ArrearEvents />, MAKER);
    await register();
    const card = (await screen.findByText(t('wf.ord.title'))).closest('.card') as HTMLElement;
    await waitFor(() => expect(card.querySelector('input[type=file]')).not.toBeDisabled());
    await waitFor(() => expect((card.querySelector('select') as HTMLSelectElement).options.length).toBe(1));
    const [, orderDate, , file] = Array.from(card.querySelectorAll('input')) as [
      HTMLInputElement,
      HTMLInputElement,
      HTMLInputElement,
      HTMLInputElement,
    ];
    fireEvent.change(orderDate, { target: { value: '2026-07-01' } });
    await user.upload(file, new File(['%PDF'], 'a.pdf', { type: 'application/pdf' }));
    await waitFor(() => expect(sent(calls, 'POST', '/v1/arrear-events/10/documents')).toHaveLength(1));
  });
});
