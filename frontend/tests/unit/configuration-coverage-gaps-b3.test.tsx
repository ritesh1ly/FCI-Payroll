import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { StaffRates } from '@/modules/configuration';
import { backend, me, paged, problem, referenceLists, sent, t, wrap } from './support/harness';

/**
 * M-04 Staff Rate Tables as drawn (SRS-1-62, SRS-11-15, SRS-G-30, SRS-G-48; OD-121): New Rate Version and a row's
 * Edit focus the form with the pattern, the row selection drives the order card, the DA and city Views, the
 * checker's tick, the submission refused or cancelled, and the order copy stored on the chosen version.
 */

const scroll = vi.fn();
beforeEach(() => {
  scroll.mockClear();
  Element.prototype.scrollIntoView = scroll;
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const REFERENCE = referenceLists({
  DA_RATE_PATTERN: [
    ['IDA', 'IDA'],
    ['CDA', 'CDA'],
  ],
  ORDER_COPY_DOCUMENT_TYPE: [['CIRCULAR', 'Circular']],
});

const DA = {
  id: 7,
  code: 'DA_IDA',
  pattern: 'IDA',
  rate: 50,
  computedOn: null,
  effectiveFrom: '2026-04-01',
  effectiveTo: null,
  versionNo: 1,
  arrearReference: null,
  arrearAmount: null,
  status: 'ACTIVE',
};

const VERSIONS = [
  DA,
  {
    ...DA,
    id: 8,
    code: 'DA_CDA',
    pattern: 'CDA',
    versionNo: 2,
    rate: null,
    computedOn: 'Basic + Stagnation',
    effectiveTo: '2027-03-31',
    arrearReference: 'AE-131',
    arrearAmount: null,
    status: 'AWAITING_APPROVAL',
  },
];

const STORED = {
  id: 3,
  fileName: 'da-order.pdf',
  documentTypeCode: 'CIRCULAR',
  documentTypeName: 'Circular',
  referenceNumber: 'HQ/DA/5',
  uploadedBy: 'su.hq@fci-demo.test',
  uploadedAt: '2026-10-01T06:00:00Z',
};

function routes(over: Record<string, (url: string, init?: RequestInit) => unknown> = {}) {
  return backend({
    'GET /v1/reference/lists': () => REFERENCE,
    'GET /v1/masters/staff-rates': () => paged([]),
    'GET /v1/masters/staff-rates/dearness-versions': () => paged(VERSIONS),
    'GET /v1/masters/staff-rates/city-classes': () =>
      paged([
        {
          cityClass: 'X',
          hraPercent: null,
          hraCdaPercent: 28,
          ccaAmount: 1800,
          inForce: true,
          pending: true,
        },
        {
          cityClass: 'DOUBLE',
          hraPercent: null,
          hraCdaPercent: null,
          ccaAmount: null,
          inForce: true,
          pending: false,
        },
      ]),
    'GET /v1/masters/staff-rates/versions/7/orders': () => [STORED],
    'GET /v1/masters/staff-rates/versions/8/orders': () => [],
    'GET /v1/document-types': () => [],
    'GET /v1/masters/circulars/current': () => [{ id: 5, number: 'HQ/DA/5', subject: 'DA revision' }],
    'POST /v1/masters/staff-rates/': () => ({ status: 202, body: {} }),
    'POST /v1/masters/staff-rates/versions/8/order': () => ({ status: 201, body: STORED }),
    ...over,
  });
}

const MAKER = ['M-04:VIEW', 'M-04:CREATE'];
const rowOf = (code: string, v: number) =>
  screen.getByRole('button', { name: `${t('common.view')} ${code} v${v}` }).closest('tr') as HTMLElement;

async function fillAndSave() {
  await screen.findByRole('option', { name: 'CDA' });
  await userEvent.selectOptions(screen.getByLabelText(/Pattern/), 'CDA');
  await userEvent.type(screen.getByLabelText(/Rate \(%\)/), '48');
  await userEvent.type(screen.getByLabelText(/Computed On/), 'Basic + NPA');
  fireEvent.change(screen.getByLabelText(/Effective From/), { target: { value: '2027-01-01' } });
  await userEvent.click(screen.getByRole('button', { name: t('m04.form.save') }));
  return within(await screen.findByRole('dialog', { name: t('m04.submit.title') }));
}

async function chooseAndSubmit(dialog: ReturnType<typeof within>) {
  await dialog.findByRole('option', { name: 'HQ/DA/5 · DA revision' });
  await userEvent.selectOptions(dialog.getByLabelText(t('mst.circular')), '5');
  await userEvent.type(dialog.getByLabelText(t('common.reason')), 'DA revision');
  await userEvent.click(dialog.getByRole('button', { name: t('common.submitForApproval') }));
}

describe('M-04 drawn buttons and rows', () => {
  it('SRS_1_62_newRateVersionAndARowEditFocusTheFormWithThePattern', async () => {
    routes();
    wrap(<StaffRates />, MAKER);
    await screen.findByText('v2');
    await userEvent.click(screen.getByRole('button', { name: t('m04.newVersion') }));
    expect(scroll).toHaveBeenCalledWith({ behavior: 'smooth', block: 'center' });
    expect(screen.getByLabelText(/Pattern/)).toHaveValue('');
    await screen.findByRole('option', { name: 'IDA' });
    await userEvent.click(screen.getByRole('button', { name: `${t('common.edit')} DA_IDA v1` }));
    expect(scroll).toHaveBeenCalledTimes(2);
    expect(screen.getByLabelText(/Pattern/)).toHaveValue('IDA');
  });

  it('SRS_1_62_aVersionWithoutRateShowsADashAndItsOwnBaseAndEndDate', async () => {
    routes();
    wrap(<StaffRates />, MAKER);
    await screen.findByText('v2');
    const row = rowOf('DA_CDA', 2);
    expect(within(row).getAllByText('—').length).toBeGreaterThanOrEqual(1);
    expect(row).toHaveTextContent('Basic + Stagnation');
    expect(row).toHaveTextContent('AE-131');
    expect(within(row).getByText(t('m04.status.AWAITING_APPROVAL'))).toHaveClass('p-warn');
  });

  it('SRS_1_62_theViewOfAVersionShowsEveryFieldAndCloses', async () => {
    routes();
    wrap(<StaffRates />, ['M-04:VIEW']);
    await screen.findByText('v2');
    await userEvent.click(screen.getByRole('button', { name: `${t('common.view')} DA_CDA v2` }));
    let dialog = await screen.findByRole('dialog', { name: t('wf.viewHead', [t('screen.M-04')]) });
    expect(within(dialog).getByText('Basic + Stagnation')).toBeTruthy();
    expect(within(dialog).getByText('AE-131')).toBeTruthy();
    expect(within(dialog).getByText(t('m04.status.AWAITING_APPROVAL'))).toBeTruthy();
    expect(within(dialog).getByText('v2')).toBeTruthy();
    await userEvent.click(within(dialog).getByRole('button', { name: t('common.close') }));
    expect(screen.queryByRole('dialog')).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: `${t('common.view')} DA_IDA v1` }));
    dialog = await screen.findByRole('dialog', { name: t('wf.viewHead', [t('screen.M-04')]) });
    expect(within(dialog).getByText('50%')).toBeTruthy();
    expect(within(dialog).getByText(t('m04.computedOn.IDA'))).toBeTruthy();
  });

  it('SRS_11_15_theCityViewsTellTheirHraAndSuppressionRule', async () => {
    routes();
    wrap(<StaffRates />, ['M-04:VIEW']);
    expect(await screen.findByText(t('m04.hra.both', ['—', '28%']))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: `${t('common.view')} X` }));
    let dialog = await screen.findByRole('dialog', { name: t('wf.viewHead', [t('screen.M-04')]) });
    expect(within(dialog).getByText(t('m04.hra.both', ['—', '28%']))).toBeTruthy();
    expect(within(dialog).getByText('1,800')).toBeTruthy();
    expect(within(dialog).getByText(t('m04.suppression.lease'))).toBeTruthy();
    await userEvent.click(within(dialog).getByRole('button', { name: t('common.close') }));
    await userEvent.click(screen.getByRole('button', { name: `${t('common.view')} DOUBLE` }));
    dialog = await screen.findByRole('dialog', { name: t('wf.viewHead', [t('screen.M-04')]) });
    expect(within(dialog).getByText(t('m04.suppression.double'))).toBeTruthy();
    expect(within(dialog).getByText('—')).toBeTruthy();
  });

  it('SRS_11_15_theCityEditOpensTheRegisterAndHistoryDoesToo', async () => {
    routes();
    wrap(<StaffRates />, MAKER);
    await userEvent.click(await screen.findByRole('button', { name: `${t('common.edit')} X` }));
    await waitFor(() => expect(screen.getByTestId('m04-register').hasAttribute('open')).toBe(true));
  });

  it('SRS_1_62_historyOpensTheClosedRegister', async () => {
    routes();
    wrap(<StaffRates />, ['M-04:VIEW']);
    await screen.findByText('v2');
    await userEvent.click(screen.getByRole('button', { name: t('mst.history') }));
    await waitFor(() => expect(screen.getByTestId('m04-register').hasAttribute('open')).toBe(true));
  });

  it('M08_08_theCheckerTickGoesToTheInboxForAWaitingVersionOrCity', async () => {
    const assign = vi.fn();
    routes();
    vi.stubGlobal('location', { ...window.location, assign });
    wrap(<StaffRates />, ['M-04:VIEW', 'M-04:APPROVE']);
    await screen.findByText('v2');
    await userEvent.click(within(rowOf('DA_IDA', 1)).getByTitle(t('wf.approveTitle')));
    expect(await screen.findByText(t('mst.nothingWaiting'))).toBeTruthy();
    expect(assign).not.toHaveBeenCalled();
    await userEvent.click(within(rowOf('DA_CDA', 2)).getByTitle(t('wf.approveTitle')));
    expect(assign).toHaveBeenCalledTimes(1);
    const city = screen.getByRole('button', { name: `${t('common.view')} X` }).closest('tr') as HTMLElement;
    await userEvent.click(within(city).getByTitle(t('wf.approveTitle')));
    expect(assign).toHaveBeenCalledTimes(2);
  });
});

describe('M-04 submission', () => {
  it('SRS_1_62_cancelClosesTheDialogAndKeepsTheDraft', async () => {
    const calls = routes();
    wrap(<StaffRates />, MAKER);
    await screen.findByText('v2');
    const dialog = await fillAndSave();
    // The circular choice can be cleared again: Submit is off once more.
    await dialog.findByRole('option', { name: 'HQ/DA/5 · DA revision' });
    await userEvent.selectOptions(dialog.getByLabelText(t('mst.circular')), '5');
    await userEvent.selectOptions(dialog.getByLabelText(t('mst.circular')), '');
    expect(dialog.getByRole('button', { name: t('common.submitForApproval') })).toBeDisabled();
    await userEvent.click(dialog.getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(screen.getByLabelText(/Rate \(%\)/)).toHaveValue(48);
    expect(sent(calls, 'POST', '/staff-rates/')).toHaveLength(0);
  });

  it('SRS_1_62_aRefusedProposalShowsTheProblemInTheDialog', async () => {
    routes({ 'POST /v1/masters/staff-rates/': () => problem(409, 'MST-409-OVERLAP') });
    wrap(<StaffRates />, MAKER);
    await screen.findByText('v2');
    const dialog = await fillAndSave();
    await chooseAndSubmit(dialog);
    expect(await dialog.findByText(t('error.MST-409-OVERLAP'))).toBeTruthy();
    expect(screen.getByRole('dialog', { name: t('m04.submit.title') })).toBeTruthy();
  });

  it('SRS_G_48_aCancelledOtpLeavesTheDialogOpenAndSilent', async () => {
    routes({ 'POST /v1/masters/staff-rates/': () => problem(428, 'AUTH-428-OTP', ['7']) });
    wrap(<StaffRates />, MAKER);
    await screen.findByText('v2');
    const dialog = await fillAndSave();
    await chooseAndSubmit(dialog);
    const otp = await screen.findByRole('dialog', { name: t('otp.title') });
    await userEvent.click(within(otp).getByRole('button', { name: t('common.cancel') }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: t('otp.title') })).toBeNull());
    expect(screen.getByRole('dialog', { name: t('m04.submit.title') })).toBeTruthy();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('SRS_1_62_withoutTheCircularListTheDialogOffersOnlyTheChoosePrompt', async () => {
    routes({ 'GET /v1/masters/circulars/current': () => problem(500, 'SYS-500') });
    wrap(<StaffRates />, MAKER);
    await screen.findByText('v2');
    const dialog = await fillAndSave();
    const select = dialog.getByLabelText(t('mst.circular')) as HTMLSelectElement;
    await waitFor(() => expect(select.options).toHaveLength(1));
    expect(select.options[0]?.textContent).toBe(t('common.choose'));
  });
});

describe('M-04 order card (SRS-G-30)', () => {
  it('SRS_G_30_theFirstVersionsStoredOrderLinksToItsFile', async () => {
    routes();
    wrap(<StaffRates />, ['M-04:VIEW']);
    const row = (await screen.findByText('da-order.pdf', {}, { timeout: 30000 })).closest(
      'tr',
    ) as HTMLElement;
    expect(
      within(row)
        .getByRole('link', { name: t('wf.view') })
        .getAttribute('href'),
    ).toContain('/v1/masters/staff-rates/versions/7/files/3');
  });

  it('SRS_G_30_selectingAVersionMovesTheCardAndAMakerStoresItsOrder', async () => {
    const calls = routes({
      'GET /v1/document-types': () => [{ code: 'CIRCULAR', referenceRequired: false }],
    });
    wrap(<StaffRates />, MAKER);
    await screen.findByText('da-order.pdf', {}, { timeout: 30000 });
    const row = rowOf('DA_CDA', 2);
    // The actions cell does not select the row.
    await userEvent.click(within(row).getByRole('button', { name: `${t('common.edit')} DA_CDA v2` }));
    expect(row).toHaveAttribute('aria-selected', 'false');
    await userEvent.click(within(row).getByText('Basic + Stagnation'));
    expect(row).toHaveAttribute('aria-selected', 'true');
    await waitFor(() => expect(screen.queryByText('da-order.pdf')).toBeNull());
    const card = screen.getByText(t('wf.ord.title')).closest('[data-order-card]') as HTMLElement;
    await within(card).findByRole('option', { name: 'Circular' });
    const inputs = card.querySelectorAll('input');
    await userEvent.type(inputs[1] as HTMLElement, '2026-01-02');
    await userEvent.upload(
      inputs[3] as HTMLInputElement,
      new File(['%PDF-1.7'], 'order.pdf', { type: 'application/pdf' }),
    );
    await waitFor(() => expect(sent(calls, 'POST', '/staff-rates/versions/8/order')).toHaveLength(1));
    const form = sent(calls, 'POST', '/staff-rates/versions/8/order')[0] as FormData;
    expect(form.get('documentType')).toBe('CIRCULAR');
    expect((form.get('file') as File).name).toBe('order.pdf');
  });

  it('SRS_G_30_aViewerCannotAttach', async () => {
    const calls = routes();
    wrap(<StaffRates />, me(['M-04:VIEW'], { roles: ['PAYROLL_MAKER'] }));
    const card = (await screen.findByText(t('wf.ord.title'))).closest('[data-order-card]') as HTMLElement;
    expect(card.querySelector('input')).toBeDisabled();
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
  });

  it('SRS_G_30_theChosenVersionStaysChosenWhenTheListIsReadAgain_evenIfItLeftThePage', async () => {
    let versions = VERSIONS;
    routes({ 'GET /v1/masters/staff-rates/dearness-versions': () => paged(versions) });
    const { client } = wrap(<StaffRates />, MAKER);
    await screen.findByText('da-order.pdf', {}, { timeout: 30000 });
    await userEvent.click(within(rowOf('DA_CDA', 2)).getByText('Basic + Stagnation'));
    await waitFor(() => expect(screen.queryByText('da-order.pdf')).toBeNull());
    // Read again with the chosen version still listed: the same version stays chosen.
    await client.invalidateQueries({ queryKey: ['masters', 'rates', 'M-04', '#dearness'] });
    await waitFor(() => expect(rowOf('DA_CDA', 2)).toHaveAttribute('aria-selected', 'true'));
    // Read again without it (another page now): the card keeps the chosen version, no other row is selected.
    versions = [DA];
    await client.invalidateQueries({ queryKey: ['masters', 'rates', 'M-04', '#dearness'] });
    await waitFor(() => expect(screen.queryByText('v2')).toBeNull());
    expect(rowOf('DA_IDA', 1)).toHaveAttribute('aria-selected', 'false');
    expect(screen.queryByText('da-order.pdf')).toBeNull();
  });

  it('SRS_G_30_withoutTheDocumentTypesTheProblemShowsAndNoTypeAsksForAReference', async () => {
    const calls = routes({
      'GET /v1/document-types': () => ({
        status: 500,
        body: { errorCode: 'SYS-500', messageKey: 'error.SYS-500', args: [] },
      }),
    });
    wrap(<StaffRates />, MAKER);
    await screen.findByText('da-order.pdf', {}, { timeout: 30000 });
    await userEvent.click(within(rowOf('DA_CDA', 2)).getByText('Basic + Stagnation'));
    const card = screen.getByText(t('wf.ord.title')).closest('[data-order-card]') as HTMLElement;
    expect(await within(card).findByText(t('error.SYS-500'))).toBeTruthy();
    await within(card).findByRole('option', { name: 'Circular' });
    const inputs = card.querySelectorAll('input');
    await userEvent.type(inputs[1] as HTMLElement, '2026-01-02');
    await userEvent.upload(
      inputs[3] as HTMLInputElement,
      new File(['%PDF-1.7'], 'order.pdf', { type: 'application/pdf' }),
    );
    await waitFor(() => expect(sent(calls, 'POST', '/staff-rates/versions/8/order')).toHaveLength(1));
  });
});
