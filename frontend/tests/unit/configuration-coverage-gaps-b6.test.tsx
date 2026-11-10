import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Circulars, ComponentCatalogue, Labels, ReferenceLists } from '@/modules/configuration';
import { backend, me, paged, problem, referenceLists, sent, t, wrap } from './support/harness';

/**
 * M-16 Reference and Combo List Administration, the drawn New Value card (SRS-G-1, SRS-G-6, SRS l.1451, SRS-G-48):
 * the full proposal with the code typed over, the Hindi label, the order and the end date; the system list refused
 * on the card; New Value bringing the card into view.
 */

afterEach(() => vi.unstubAllGlobals());

const governed = (answer: unknown) => (_url: string, init?: RequestInit) =>
  (JSON.parse(String(init?.body)) as { otp?: unknown }).otp ? answer : problem(428, 'AUTH-428-OTP', [7]);

const value = (over: object = {}) => ({
  code: 'MONTHLY',
  labelEn: 'Monthly',
  labelHi: null,
  sortOrder: 1,
  effectiveFrom: '2026-04-01',
  effectiveTo: null,
  active: true,
  versionNo: 1,
  changedBy: 'system',
  changedAt: null,
  pending: null,
  ...over,
});

const LISTS = [
  {
    code: 'LEAVE_FREQUENCY',
    name: 'Leave frequency',
    module: 'LEAVE',
    system: false,
    values: [value()],
    pendingNew: [],
  },
  {
    code: 'CLAIM_TYPE',
    name: 'Claim type',
    module: 'ALLOWANCE',
    system: true,
    values: [value({ code: 'MEDICAL', labelEn: 'Medical' })],
    pendingNew: [],
  },
];
const ALL = ['M-16:VIEW', 'M-16:CREATE', 'M-16:EDIT'];
const label = (key: string) => screen.getByLabelText(new RegExp(`^${t(key)}`));

describe('M-16 drawn New Value card', () => {
  it('SRS_G_1_everyFieldOfTheCardIsSentWithTheOtp_theCardEmptiesAfterwards', async () => {
    const calls = backend({
      'GET /v1/masters/reference-lists': () => LISTS,
      'POST /v1/masters/reference-lists/': governed({ status: 202, body: {} }),
    });
    wrap(<ReferenceLists />, ALL);
    await screen.findByRole('heading', { name: t('m16w.list.LEAVE_FREQUENCY') });
    await userEvent.selectOptions(label('m16w.listName'), 'LEAVE_FREQUENCY');
    await userEvent.type(label('m16w.value'), 'Bi-Annually');
    fireEvent.change(label('m16w.from'), { target: { value: '2026-12-01' } });
    await userEvent.type(label('m16w.reason'), ' HR circular ');
    const more = document.getElementById('m16-new-code') as HTMLInputElement;
    expect(more).toHaveValue('BI_ANNUALLY');
    await userEvent.clear(more);
    await userEvent.type(more, 'half_yearly');
    expect(more).toHaveValue('HALF_YEARLY');
    await userEvent.type(document.getElementById('m16-new-hi') as HTMLElement, 'अर्धवार्षिक');
    await userEvent.type(document.getElementById('m16-new-order') as HTMLElement, '4');
    fireEvent.change(document.getElementById('m16-new-to') as HTMLElement, {
      target: { value: '2027-03-31' },
    });
    await userEvent.click(screen.getByRole('button', { name: t('m16w.save') }));
    await userEvent.type(await screen.findByLabelText(t('otp.code')), '123456');
    await userEvent.click(screen.getByRole('button', { name: t('otp.verify') }));
    await waitFor(() => expect(sent(calls, 'POST', '/LEAVE_FREQUENCY/values')).toHaveLength(2));
    expect(sent(calls, 'POST', '/LEAVE_FREQUENCY/values').at(-1)).toMatchObject({
      code: 'HALF_YEARLY',
      labelEn: 'Bi-Annually',
      labelHi: 'अर्धवार्षिक',
      sortOrder: 4,
      effectiveFrom: '2026-12-01',
      effectiveTo: '2027-03-31',
      reason: 'HR circular',
    });
    expect(await screen.findByText(t('m16.submitted', ['LEAVE_FREQUENCY']))).toBeTruthy();
    expect(label('m16w.value')).toHaveValue('');
    // The code follows the text again once the card is emptied.
    await userEvent.type(label('m16w.value'), 'Weekly');
    expect(document.getElementById('m16-new-code')).toHaveValue('WEEKLY');
  });

  it('SRS_G_6_aSystemListChosenOnTheCardSaysItKeepsItsCodesAndSendsNothing', async () => {
    const calls = backend({ 'GET /v1/masters/reference-lists': () => LISTS });
    wrap(<ReferenceLists />, ALL);
    await screen.findByRole('heading', { name: t('m16w.list.LEAVE_FREQUENCY') });
    await userEvent.selectOptions(label('m16w.listName'), 'CLAIM_TYPE');
    await userEvent.type(label('m16w.value'), 'Dental');
    await userEvent.type(label('m16w.reason'), 'HR circular');
    const save = screen.getByRole('button', { name: t('m16w.save') });
    expect(save).toHaveAttribute('data-guard', t('m16.systemHint'));
    const before = screen.queryAllByText(t('m16.systemHint')).length;
    await userEvent.click(save);
    await waitFor(() => expect(screen.queryAllByText(t('m16.systemHint')).length).toBeGreaterThan(before));
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
  });

  it('SRS_G_1_newValueBringsTheCardIntoViewWhenTheBrowserCanScroll', async () => {
    backend({ 'GET /v1/masters/reference-lists': () => LISTS });
    wrap(<ReferenceLists />, ALL);
    await screen.findByRole('heading', { name: t('m16w.list.LEAVE_FREQUENCY') });
    const button = screen.getByRole('button', { name: t('m16w.new') });
    await userEvent.click(button);
    const scroll = vi.fn();
    Element.prototype.scrollIntoView = scroll;
    try {
      await userEvent.click(button);
      expect(scroll).toHaveBeenCalledTimes(1);
    } finally {
      delete (Element.prototype as { scrollIntoView?: unknown }).scrollIntoView;
    }
  });

  it('SRS_16_15_outsideHeadquartersNewValueIsLockedWithTheHqReason', async () => {
    backend({ 'GET /v1/masters/reference-lists': () => LISTS });
    wrap(<ReferenceLists />, me(ALL, { atHeadquarters: false, hqOnlyScreens: ['M-16'] }));
    const locked = await screen.findByRole('button', { name: t('m16w.new') });
    expect(locked).toBeDisabled();
    expect(locked).toHaveAttribute('data-why', t('error.AUTHZ-403-HQ-ONLY'));
    expect(label('m16w.value')).toBeDisabled();
    expect(within(document.body).getByRole('button', { name: t('m16w.save') })).toBeTruthy();
  });
});

describe('M-26 labels row selection (SRS-G-39)', () => {
  it('SRS_G_39_clickingALabelRowSelectsIt_theActionsCellDoesNot', async () => {
    const row = (id: number, key: string, text: string) => ({
      id,
      key,
      type: 'UI_LABEL',
      masterEntity: null,
      masterCode: null,
      language: 'EN',
      scope: 'GLOBAL',
      scopeLocationId: null,
      scopeOffice: null,
      text,
      localisable: false,
      versionNo: 1,
      effectiveFrom: '2026-04-01',
      effectiveTo: null,
      status: 'ACTIVE',
      approvalId: 1,
      changedBy: 'seed',
    });
    backend({
      'GET /v1/reference/lists': () => referenceLists({}),
      'GET /v1/masters/labels/missing-hindi': () => paged([]),
      'GET /v1/masters/labels': () => paged([row(11, 'LBL.A', 'Gang'), row(12, 'LBL.B', 'Depot')]),
    });
    wrap(<Labels />, ['M-26:VIEW']);
    const depot = (await screen.findByText('Depot')).closest('tr') as HTMLElement;
    const gang = screen.getByText('Gang').closest('tr') as HTMLElement;
    await userEvent.click(within(depot).getByText('LBL.B'));
    expect(depot).toHaveAttribute('aria-selected', 'true');
    expect(gang).toHaveAttribute('aria-selected', 'false');
    await userEvent.click(within(gang).getByText('Gang'));
    expect(gang).toHaveAttribute('aria-selected', 'true');
    expect(depot).toHaveAttribute('aria-selected', 'false');
  });
});

describe('M-07 New Component (SRS-G-42)', () => {
  it('SRS_G_42_theCreateHolderNewBringsTheEmptyFormIntoView', async () => {
    backend({
      'GET /v1/reference/lists': () => referenceLists({}),
      'GET /v1/pay-components?': (url: string) => paged([], 0, url),
    });
    wrap(<ComponentCatalogue />, me(['M-07:VIEW', 'M-07:CREATE', 'M-07:EDIT']));
    const button = await screen.findByRole('button', { name: t('m07w.new') });
    await userEvent.click(button);
    const scroll = vi.fn();
    Element.prototype.scrollIntoView = scroll;
    try {
      await userEvent.click(button);
      expect(scroll).toHaveBeenCalledTimes(1);
    } finally {
      delete (Element.prototype as { scrollIntoView?: unknown }).scrollIntoView;
    }
  });
});

describe('M-25 circular view (SRS-G-38)', () => {
  it('SRS_G_38_aCircularForNoPayrollTypeShowsADashInItsView', async () => {
    backend({
      'GET /v1/reference/lists': () => referenceLists({}),
      'GET /v1/masters/circulars/5/where-used': () => paged([]),
      'GET /v1/masters/circulars': () =>
        paged([
          {
            id: 5,
            number: 'EP-01/2026/GEN-01',
            date: '2026-07-04',
            issuingAuthority: 'HQ_PERSONNEL',
            issuingLocationId: 1,
            subject: 'General order',
            category: 'LABOUR_NORM',
            appliesAllOffices: true,
            payrollTypes: [],
            offices: [],
            effectiveFrom: '2026-07-01',
            effectiveTo: null,
            supersedesId: null,
            supersedesNumber: null,
            documentId: 3,
            status: 'ACTIVE',
            statusReason: null,
            approvalId: 1,
            createdBy: 'maker',
            createdAt: '2026-07-04T00:00:00Z',
            changedAt: null,
            whereUsed: 0,
          },
        ]),
    });
    wrap(<Circulars />, ['M-25:VIEW']);
    await userEvent.click(
      await screen.findByRole('button', { name: `${t('common.view')} EP-01/2026/GEN-01` }),
    );
    const dialog = await screen.findByRole('dialog', { name: t('wf.viewHead', [t('screen.M-25')]) });
    const key = within(dialog).getByText(t('m25.col.payrollTypes')).closest('.k') as HTMLElement;
    expect(key.nextElementSibling?.textContent).toBe('—');
  });
});
