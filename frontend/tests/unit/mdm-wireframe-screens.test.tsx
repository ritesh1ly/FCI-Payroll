import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Circulars, Labels, Operations } from '@/modules/configuration';
import { date } from '@/shared/ui';
import { backend, paged, referenceLists, t, wrap } from './support/harness';

/**
 * M-25, M-26 and M-27 as Wireframes v6.1 draws them (OD-121; SRS-G-38, SRS-G-39, SRS-G-41): the
 * drawn toolbars for each kind of role, the columns with their badges, the disabled reasons, the
 * row's ✔ of the Checker, the read-only record dialog, the actions on the selected row and the
 * empty list.
 */

afterEach(() => vi.unstubAllGlobals());

const REFERENCE = referenceLists({
  ISSUING_AUTHORITY: [['HQ_PERSONNEL', 'HQ Personnel']],
  CIRCULAR_CATEGORY: [['LABOUR_NORM', 'Labour Norm']],
  LANGUAGE: [
    ['EN', 'English'],
    ['HI', 'Hindi'],
  ],
});

/** The column titles of the table under the heading, with their source letters as class names. */
function columns(heading: string): string[] {
  const card = screen.getByRole('heading', { name: heading }).closest('.card') as HTMLElement;
  return Array.from(card.querySelectorAll('thead th')).map((th) => {
    const copy = th.cloneNode(true) as HTMLElement;
    const badge = copy.querySelector('.src');
    const source = /s-([A-Z]+)/.exec(badge?.className ?? '')?.[1] ?? '';
    badge?.remove();
    return `${copy.textContent} [${source}]`;
  });
}

const circular = (over: object = {}) => ({
  id: 5,
  number: 'EP-01/2026/DA-07',
  date: '2026-07-04',
  issuingAuthority: 'HQ_PERSONNEL',
  issuingLocationId: 1,
  subject: 'IDA DA revision',
  category: 'LABOUR_NORM',
  appliesAllOffices: true,
  payrollTypes: ['STAFF'],
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
  whereUsed: 3,
  ...over,
});

function circulars(rows: unknown[]) {
  return backend({
    'GET /v1/reference/lists': () => REFERENCE,
    'GET /v1/masters/circulars/5/where-used': () => paged([]),
    'GET /v1/masters/circulars/6/where-used': () => paged([]),
    'GET /v1/masters/circulars/7/where-used': () => paged([]),
    'GET /v1/masters/circulars': () => paged(rows),
  });
}

describe('M-25 as drawn', () => {
  it('SRS-G-38: the toolbar, the register with its badges, its count, and the two cards under it', async () => {
    circulars([circular()]);
    wrap(<Circulars />, ['M-25:VIEW', 'M-25:CREATE', 'M-25:EDIT', 'M-25:EXPORT']);
    await screen.findByText('EP-01/2026/DA-07');
    expect(
      Array.from(document.querySelectorAll('.wireframe-body-container > .toolbar .btn')).map(
        (b) => b.textContent,
      ),
    ).toEqual([
      t('wf.historyAct'),
      t('m25.act.whereUsed'),
      t('m25.act.new'),
      t('m25.act.supersede'),
      t('m25.act.withdraw'),
    ]);
    expect(columns(t('m25.table'))).toEqual([
      `${t('m25.col.number')} [ENT]`,
      `${t('m25.col.date')} [ENT]`,
      `${t('m25.col.authority')} [ENT]`,
      `${t('m25.col.subject')} [ENT]`,
      `${t('m25.col.category')} [ENT]`,
      `${t('m25.col.payrollTypes')} [ENT]`,
      `${t('m25.col.region')} [ENT]`,
      `${t('m25.col.effective')} [ENT]`,
      `${t('m25.col.supersedes')} [ENT]`,
      `${t('m25.col.document')} [ENT]`,
      `${t('m25.col.whereUsed')} [SYS]`,
      `${t('wf.col.status')} [SYS]`,
      `${t('wf.col.actions')} [SYS]`,
    ]);
    expect(columns(t('m25.whereUsedOf', ['EP-01/2026/DA-07']))).toEqual([
      `${t('m25.col.linkedItem')} [SYS]`,
      `${t('m25.col.type')} [SYS]`,
      `${t('m25.col.screen')} [SYS]`,
    ]);
    expect(screen.getByRole('button', { name: t('action.export') })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: t('m25.panel.title') })).toBeInTheDocument();
    expect(screen.getByText('3')).toBeInTheDocument();
    expect(screen.getAllByText(t('m25.all')).some((el) => el.tagName === 'TD')).toBe(true);
    expect(screen.getByText(`${date('2026-07-01')} –`)).toBeInTheDocument();
  });

  it('SRS-G-38: a closed period shows both dates; a dated row with no start shows a dash', async () => {
    circulars([
      circular({ effectiveTo: '2026-12-31' }),
      circular({ id: 6, number: 'X/6', effectiveFrom: null, documentId: null, payrollTypes: [] }),
    ]);
    wrap(<Circulars />, ['M-25:VIEW']);
    expect(await screen.findByText(`${date('2026-07-01')} – ${date('2026-12-31')}`)).toBeInTheDocument();
    expect(screen.getByText('— –')).toBeInTheDocument();
  });

  it('SRS M-25: a role without the export right is told why, nothing is fetched', async () => {
    const calls = circulars([circular()]);
    wrap(<Circulars />, ['M-25:VIEW']);
    await screen.findByText('EP-01/2026/DA-07');
    await userEvent.click(screen.getByRole('button', { name: t('action.export') }));
    expect(await screen.findByText(t('m25.why.export'))).toBeInTheDocument();
    expect(calls.some((c) => c.url.includes('/circulars/export'))).toBe(false);
  });

  it('SRS-G-38: Supersede and Withdraw act only on an Active circular', async () => {
    circulars([circular({ id: 6, number: 'OLD/6', status: 'SUPERSEDED' })]);
    wrap(<Circulars />, ['M-25:VIEW', 'M-25:CREATE', 'M-25:EDIT']);
    await screen.findByText('OLD/6');
    await userEvent.click(screen.getByRole('button', { name: t('m25.act.supersede') }));
    expect(await screen.findByText(t('m25.why.notActive'))).toBeInTheDocument();
    expect(screen.queryByText(t('m25.supersedeTitle'))).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: t('m25.act.withdraw') }));
    expect(screen.queryByRole('dialog')).toBeNull();
    // A superseded record cannot be edited, with the reason.
    const edit = screen.getByRole('button', { name: `${t('common.edit')} OLD/6` });
    expect(edit).toBeDisabled();
    expect(edit.getAttribute('data-why')).toBe(t('m25.why.notActive'));
  });

  it('SRS-G-38: the row Edit of an Active circular starts a superseding one; an open form locks the rows', async () => {
    circulars([circular()]);
    wrap(<Circulars />, ['M-25:VIEW', 'M-25:CREATE']);
    const name = `${t('common.edit')} EP-01/2026/DA-07`;
    await userEvent.click(await screen.findByRole('button', { name }));
    expect(screen.getByText(t('m25.supersedeTitle'))).toBeInTheDocument();
    const edit = screen.getByRole('button', { name });
    expect(edit).toBeDisabled();
    expect(edit.getAttribute('data-why')).toBe(t('m25.why.formOpen'));
  });

  it('SRS-G-38: a role that only views reads the drawn reason on the row Edit', async () => {
    circulars([circular()]);
    wrap(<Circulars />, ['M-25:VIEW']);
    const edit = await screen.findByRole('button', { name: `${t('common.edit')} EP-01/2026/DA-07` });
    expect(edit).toBeDisabled();
    expect(edit.getAttribute('data-why')).toBe(t('wf.viewOnly'));
  });

  it('SRS-G-38: the Checker ✔ goes to the inbox for a pending circular, and says so otherwise; none on a closed one', async () => {
    const assign = vi.fn();
    vi.stubGlobal('location', { ...window.location, assign });
    circulars([
      circular(),
      circular({ id: 6, number: 'NEW/6', status: 'PENDING_APPROVAL' }),
      circular({ id: 7, number: 'OLD/7', status: 'SUPERSEDED' }),
    ]);
    wrap(<Circulars />, ['M-25:VIEW', 'M-25:APPROVE']);
    await screen.findByText('NEW/6');
    const approve = (number: string) =>
      within(screen.getByText(number).closest('tr') as HTMLElement).queryByRole('button', { name: '✔' });
    expect(approve('OLD/7')).toBeNull();
    await userEvent.click(approve('EP-01/2026/DA-07') as HTMLElement);
    expect(await screen.findByText(t('wf.nothingPending'))).toBeInTheDocument();
    expect(assign).not.toHaveBeenCalled();
    await userEvent.click(approve('NEW/6') as HTMLElement);
    expect(assign).toHaveBeenCalledWith(expect.stringContaining('s=W-01'));
  });

  it('SRS-G-38: View shows the record read-only with its order copy and the reason of a withdrawal', async () => {
    circulars([
      circular({
        status: 'WITHDRAWN',
        statusReason: 'Reissued',
        supersedesNumber: 'OLD/1',
        appliesAllOffices: false,
        offices: [{ scopeType: 'LOCATION', payrollType: null, locationId: 9, office: 'DH001 Delhi' }],
      }),
    ]);
    wrap(<Circulars />, ['M-25:VIEW']);
    await userEvent.click(
      await screen.findByRole('button', { name: `${t('common.view')} EP-01/2026/DA-07` }),
    );
    const dialog = within(screen.getByRole('dialog', { name: t('wf.viewHead', [t('screen.M-25')]) }));
    expect(dialog.getByText('Reissued')).toBeInTheDocument();
    expect(dialog.getByText('OLD/1')).toBeInTheDocument();
    expect(dialog.getByText('DH001 Delhi')).toBeInTheDocument();
    expect(dialog.getByRole('link', { name: t('m25.document') }).getAttribute('href')).toContain(
      '/v1/masters/circulars/5/document',
    );
    await userEvent.click(dialog.getByRole('button', { name: t('common.close') }));
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('SRS-G-38: a record without a copy offers none; Escape closes the record', async () => {
    circulars([circular({ documentId: null })]);
    wrap(<Circulars />, ['M-25:VIEW']);
    await userEvent.click(
      await screen.findByRole('button', { name: `${t('common.view')} EP-01/2026/DA-07` }),
    );
    expect(within(screen.getByRole('dialog')).queryByRole('link')).toBeNull();
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  });

  it('SRS-G-38: with no circular, History and Where used ask for a row and nothing breaks', async () => {
    circulars([]);
    wrap(<Circulars />, ['M-25:VIEW', 'M-25:CREATE', 'M-25:EDIT']);
    await screen.findByText(t('m25.none'));
    await userEvent.click(screen.getByRole('button', { name: t('wf.historyAct') }));
    expect(await screen.findByText(t('wf.selectRow'))).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: t('m25.act.whereUsed') }));
    await userEvent.click(screen.getByRole('button', { name: t('m25.act.supersede') }));
    await userEvent.click(screen.getByRole('button', { name: t('m25.act.withdraw') }));
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(screen.getByRole('heading', { name: t('m25.whereUsed') })).toBeInTheDocument();
  });

  it('SRS-G-38: Where used brings the card of the selected circular into view; History opens its trail', async () => {
    const scroll = vi.fn();
    Element.prototype.scrollIntoView = scroll;
    circulars([circular()]);
    wrap(<Circulars />, ['M-25:VIEW']);
    await screen.findByText('EP-01/2026/DA-07');
    await userEvent.click(screen.getByRole('button', { name: t('m25.act.whereUsed') }));
    expect(scroll).toHaveBeenCalled();
    await userEvent.click(screen.getByRole('button', { name: t('history.for', ['EP-01/2026/DA-07']) }));
    expect(await screen.findByRole('dialog')).toBeInTheDocument();
  });
});

const label = (over: object = {}) => ({
  id: 11,
  key: 'LBL.WORKSLIP.GANG',
  type: 'UI_LABEL',
  masterEntity: null,
  masterCode: null,
  language: 'EN',
  scope: 'GLOBAL',
  scopeLocationId: null,
  scopeOffice: null,
  text: 'Gang',
  localisable: false,
  versionNo: 1,
  effectiveFrom: '2026-04-01',
  effectiveTo: null,
  status: 'ACTIVE',
  approvalId: 1,
  changedBy: 'seed',
  ...over,
});

const LOCAL = label({
  id: 12,
  key: 'DESIG.HL',
  type: 'MASTER_VALUE',
  masterEntity: 'ORG_DESIGNATION',
  masterCode: 'HL',
  localisable: true,
  scope: 'OFFICE',
  scopeOffice: 'DO Pune',
  text: 'Godown Mazdoor',
  status: 'PENDING_APPROVAL',
});

function labels(rows: unknown[], missing: unknown[] = []) {
  return backend({
    'GET /v1/reference/lists': () => REFERENCE,
    'GET /v1/masters/labels/missing-hindi': () => paged(missing),
    'GET /v1/masters/labels': () => paged(rows),
  });
}

describe('M-26 as drawn', () => {
  it('SRS-G-39: the toolbar, the Labels table with its badges, the statuses and the Display rules', async () => {
    labels([label(), LOCAL]);
    wrap(<Labels />, ['M-26:VIEW', 'M-26:CREATE', 'M-26:EDIT', 'M-26:EXPORT']);
    await screen.findByText('Godown Mazdoor');
    expect(
      Array.from(document.querySelectorAll('.wireframe-body-container > .toolbar .btn')).map(
        (b) => b.textContent,
      ),
    ).toEqual([t('wf.historyAct'), t('m26.act.download'), t('m26.act.upload'), t('m26.act.missing')]);
    expect(columns(t('m26.table'))).toEqual([
      `${t('m26.col.key')} [ENT]`,
      `${t('m26.col.type')} [ENT]`,
      `${t('m26.col.language')} [ENT]`,
      `${t('m26.col.scope')} [ENT]`,
      `${t('m26.col.text')} [ENT]`,
      `${t('m26.col.localisable')} [ENT]`,
      `${t('m26.col.effective')} [ENT]`,
      `${t('wf.col.status')} [SYS]`,
      `${t('wf.col.actions')} [SYS]`,
    ]);
    expect(screen.getByText(t('m26.status.PROPOSED'))).toBeInTheDocument();
    expect(screen.getByText(`${t('m26.scope.OFFICE')} — DO Pune`)).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: t('m26.panel.title') })).toBeInTheDocument();
    expect(screen.getByText(t('m26.panel.blank'))).toBeInTheDocument();
  });

  it('SRS-G-39: a pending Global label is not "RO to approve"; Hindi text is marked as Hindi', async () => {
    labels([label({ status: 'PENDING_APPROVAL' }), label({ id: 13, language: 'HI', text: 'गैंग' })]);
    wrap(<Labels />, ['M-26:VIEW']);
    expect(await screen.findByText(t('m26.status.PENDING_APPROVAL'))).toBeInTheDocument();
    expect(screen.queryByText(t('m26.status.PROPOSED'))).toBeNull();
    expect(screen.getByText('गैंग').getAttribute('lang')).toBe('hi');
  });

  it('SRS-G-39: a role without EXPORT is told why on Bulk download and on Export', async () => {
    const calls = labels([label()]);
    wrap(<Labels />, ['M-26:VIEW']);
    await screen.findByText('Gang');
    await userEvent.click(screen.getByRole('button', { name: t('m26.act.download') }));
    expect(await screen.findByText(t('m26.why.export'))).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: t('action.export') }));
    expect(calls.some((c) => c.url.includes('/labels/export'))).toBe(false);
  });

  it('SRS-G-39: an office proposes a local name from a localisable row only, as a local scope', async () => {
    const pendingLabel = label({
      id: 14,
      key: 'DESIG.SARDAR',
      type: 'MASTER_VALUE',
      masterEntity: 'ORG_DESIGNATION',
      masterCode: 'SARDAR',
      localisable: true,
      text: 'Sardar',
    });
    labels([label(), pendingLabel]);
    wrap(<Labels />, ['M-26:VIEW', 'M-26:CREATE']);
    await screen.findByText('Sardar');
    const screenText = screen.getByRole('button', { name: `${t('common.edit')} LBL.WORKSLIP.GANG` });
    expect(screenText).toBeDisabled();
    expect(screenText.getAttribute('data-why')).toBe(t('wf.why.adminMaintained'));
    await userEvent.click(screen.getByRole('button', { name: `${t('common.edit')} DESIG.SARDAR` }));
    expect((screen.getAllByLabelText(t('m26.scope')).at(-1) as HTMLSelectElement).value).toBe('OFFICE');
    expect(screen.getByText(t('m26.localNote'))).toBeInTheDocument();
    // The form is open: the row says why it waits.
    expect(
      screen.getByRole('button', { name: `${t('common.edit')} DESIG.SARDAR` }).getAttribute('data-why'),
    ).toBe(t('m26.why.formOpen'));
  });

  it('SRS-G-39: a label not Active cannot be revised, with the reason; a viewer reads the drawn reason', async () => {
    labels([label({ status: 'SUPERSEDED' })]);
    const { unmount } = wrap(<Labels />, ['M-26:VIEW', 'M-26:CREATE', 'M-26:EDIT']);
    const name = `${t('common.edit')} LBL.WORKSLIP.GANG`;
    expect((await screen.findByRole('button', { name })).getAttribute('data-why')).toBe(
      t('m26.why.notActive'),
    );
    unmount();
    labels([label()]);
    wrap(<Labels />, ['M-26:VIEW']);
    expect((await screen.findByRole('button', { name })).getAttribute('data-why')).toBe(
      t('wf.why.adminMaintained'),
    );
  });

  it('SRS-G-39: the Checker ✔ goes to the inbox for a proposal, and says so otherwise', async () => {
    const assign = vi.fn();
    vi.stubGlobal('location', { ...window.location, assign });
    labels([label(), LOCAL]);
    wrap(<Labels />, ['M-26:VIEW', 'M-26:APPROVE']);
    await screen.findByText('Godown Mazdoor');
    const approve = (text: string) =>
      within(screen.getByText(text).closest('tr') as HTMLElement).getByRole('button', { name: '✔' });
    await userEvent.click(approve('Gang'));
    expect(await screen.findByText(t('wf.nothingPending'))).toBeInTheDocument();
    await userEvent.click(approve('Godown Mazdoor'));
    expect(assign).toHaveBeenCalledWith(expect.stringContaining('s=W-01'));
  });

  it('SRS M-26: on Missing translations the Administrator enters the Hindi text from the row', async () => {
    labels([], [label({ key: 'OPR.W2P', type: 'MASTER_VALUE', localisable: true })]);
    wrap(<Labels />, ['M-26:VIEW', 'M-26:CREATE', 'M-26:EDIT']);
    await screen.findByText(t('m26.none'));
    await userEvent.click(screen.getByRole('button', { name: t('m26.act.missing') }));
    await userEvent.click(await screen.findByRole('button', { name: `${t('common.edit')} OPR.W2P` }));
    expect((screen.getAllByLabelText(t('m26.language')).at(-1) as HTMLSelectElement).value).toBe('HI');
  });

  it('SRS M-26: no label missing says so; with no label History asks for a row', async () => {
    labels([], []);
    wrap(<Labels />, ['M-26:VIEW']);
    await screen.findByText(t('m26.none'));
    await userEvent.click(screen.getByRole('button', { name: t('wf.historyAct') }));
    expect(await screen.findByText(t('wf.selectRow'))).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: t('m26.act.missing') }));
    expect(await screen.findByText(t('m26.noneMissing'))).toBeInTheDocument();
  });

  it('SRS-G-39: View shows the label read-only with its dates and its label history', async () => {
    labels([label({ effectiveTo: '2026-12-31' })]);
    wrap(<Labels />, ['M-26:VIEW']);
    await userEvent.click(
      await screen.findByRole('button', { name: `${t('common.view')} LBL.WORKSLIP.GANG` }),
    );
    const dialog = within(screen.getByRole('dialog', { name: t('wf.viewHead', [t('screen.M-26')]) }));
    expect(dialog.getByText(date('2026-12-31'))).toBeInTheDocument();
    expect(dialog.getByText('v1')).toBeInTheDocument();
    await userEvent.click(dialog.getByRole('button', { name: t('common.close') }));
    await userEvent.click(screen.getByRole('button', { name: t('history.for', ['LBL.WORKSLIP.GANG']) }));
    expect(await screen.findByRole('dialog')).toBeInTheDocument();
  });
});

const op = (over: object = {}) => ({
  id: 21,
  versionNo: 1,
  effectiveFrom: '2026-04-01',
  effectiveTo: null,
  name: 'Wagon-to-Platform',
  departmental: true,
  dps: true,
  nwnp: true,
  bagBased: true,
  normType: 'HANDLING',
  rateNormLink: 'NORM_SLAB',
  dusting: false,
  allowancePayCode: null,
  presentButNoWork: false,
  circularId: 5,
  circularNumber: 'IR(L)/4/2026',
  status: 'APPROVED',
  approvalId: 1,
  ...over,
});

function operations(rows: unknown[]) {
  return backend({
    'GET /v1/masters/circulars/current': () => [],
    'GET /v1/masters/operations': () => paged(rows),
  });
}

describe('M-27 as drawn', () => {
  it('SRS-G-41: the toolbar and the Operations table with its badges', async () => {
    operations([{ code: 'W2P', current: op(), pending: null }]);
    wrap(<Operations />, ['M-27:VIEW', 'M-27:CREATE', 'M-27:EDIT', 'M-27:EXPORT']);
    await screen.findByText('Wagon-to-Platform');
    expect(
      Array.from(document.querySelectorAll('.wireframe-body-container > .toolbar .btn')).map(
        (b) => b.textContent,
      ),
    ).toEqual([t('wf.historyAct'), t('m27.act.new')]);
    expect(columns(t('m27.table'))).toEqual([
      `${t('m27.col.code')} [ENT]`,
      `${t('m27.col.name')} [ENT]`,
      `${t('m27.col.payrolls')} [ENT]`,
      `${t('m27.col.bagBased')} [ENT]`,
      `${t('m27.col.band')} [RULE]`,
      `${t('m27.col.link')} [RULE]`,
      `${t('m27.col.circular')} [ENT]`,
      `${t('m27.col.effective')} [ENT]`,
      `${t('wf.col.status')} [SYS]`,
      `${t('wf.col.actions')} [SYS]`,
    ]);
    expect(screen.getByRole('button', { name: t('action.export') })).toBeInTheDocument();
    expect(screen.getByText('Y')).toBeInTheDocument();
    expect(screen.getByText(t('m27.linkShown.NORM_SLAB'))).toBeInTheDocument();
    expect(screen.getByText(t('m27.status.ACTIVE'))).toBeInTheDocument();
  });

  it('SRS-G-41: Dusting and Present But No Work name what pays them; a time-based operation has no band', async () => {
    operations([
      {
        code: 'DUST',
        current: op({
          name: 'Dusting',
          bagBased: false,
          dusting: true,
          rateNormLink: 'ALLOWANCE',
          allowancePayCode: 9177,
        }),
        pending: null,
      },
      {
        code: 'PBNW',
        current: op({
          name: 'Present But No Work',
          bagBased: false,
          presentButNoWork: true,
          rateNormLink: 'TIME_BASED',
          circularNumber: null,
          circularId: null,
          normType: null,
        }),
        pending: null,
      },
    ]);
    wrap(<Operations />, ['M-27:VIEW']);
    expect(await screen.findByText(t('m27.linkShown.DUSTING'))).toBeInTheDocument();
    expect(screen.getByText(t('m27.linkShown.PBNW'))).toBeInTheDocument();
    expect(screen.queryByText(t('m27.bandFromMaster'))).toBeNull();
    // The record of Dusting names its pay code; the one without a norm type shows a dash.
    await userEvent.click(screen.getByRole('button', { name: `${t('common.view')} DUST` }));
    expect(within(screen.getByRole('dialog')).getByText('9177')).toBeInTheDocument();
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.close') }),
    );
    await userEvent.click(screen.getByRole('button', { name: `${t('common.view')} PBNW` }));
    expect(within(screen.getByRole('dialog')).getAllByText('—').length).toBeGreaterThan(1);
  });

  it('SRS M-27: a role without EXPORT is told why; a viewer reads the drawn reason on Edit', async () => {
    const calls = operations([{ code: 'W2P', current: op(), pending: null }]);
    wrap(<Operations />, ['M-27:VIEW']);
    await screen.findByText('Wagon-to-Platform');
    await userEvent.click(screen.getByRole('button', { name: t('action.export') }));
    expect(await screen.findByText(t('m27.why.export'))).toBeInTheDocument();
    expect(calls.some((c) => c.url.includes('/operations/export'))).toBe(false);
    expect(screen.getByRole('button', { name: `${t('common.edit')} W2P` }).getAttribute('data-why')).toBe(
      t('wf.why.adminMaintained'),
    );
  });

  it('SRS-G-41: one version at a time: a pending version and an open form lock the row, with the reason', async () => {
    operations([
      { code: 'W2P', current: op(), pending: op({ versionNo: 2, effectiveFrom: '2027-01-01' }) },
      { code: 'T2P', current: op({ name: 'Truck-to-Platform' }), pending: null },
    ]);
    wrap(<Operations />, ['M-27:VIEW', 'M-27:CREATE']);
    await screen.findByText('Truck-to-Platform');
    expect(screen.getByText(t('mst.pendingVersion', [2, date('2027-01-01')]))).toBeInTheDocument();
    expect(screen.getByRole('button', { name: `${t('common.edit')} W2P` }).getAttribute('data-why')).toBe(
      t('m27.why.pending'),
    );
    await userEvent.click(screen.getByRole('button', { name: `${t('common.edit')} T2P` }));
    expect(screen.getByRole('button', { name: `${t('common.edit')} T2P` }).getAttribute('data-why')).toBe(
      t('m27.why.formOpen'),
    );
  });

  it('SRS-G-41: the Checker ✔ goes to the inbox for a pending version, and says so otherwise', async () => {
    const assign = vi.fn();
    vi.stubGlobal('location', { ...window.location, assign });
    operations([
      { code: 'W2P', current: op(), pending: null },
      { code: 'T2P', current: op({ name: 'Truck-to-Platform' }), pending: op({ versionNo: 2 }) },
    ]);
    wrap(<Operations />, ['M-27:VIEW', 'M-27:APPROVE']);
    await screen.findByText('Truck-to-Platform');
    const approve = (text: string) =>
      within(screen.getByText(text).closest('tr') as HTMLElement).getByRole('button', { name: '✔' });
    await userEvent.click(approve('Wagon-to-Platform'));
    expect(await screen.findByText(t('wf.nothingPending'))).toBeInTheDocument();
    await userEvent.click(approve('Truck-to-Platform'));
    expect(assign).toHaveBeenCalledWith(expect.stringContaining('s=W-01'));
  });

  it('SRS-G-41: with no operation History asks for a row; a code with no version shows dashes', async () => {
    operations([]);
    const { unmount } = wrap(<Operations />, ['M-27:VIEW']);
    await screen.findByText(t('m27.none'));
    await userEvent.click(screen.getByRole('button', { name: t('wf.historyAct') }));
    expect(await screen.findByText(t('wf.selectRow'))).toBeInTheDocument();
    unmount();
    operations([{ code: 'NEW', current: null, pending: null }]);
    wrap(<Operations />, ['M-27:VIEW']);
    await userEvent.click(await screen.findByRole('button', { name: `${t('common.view')} NEW` }));
    expect(within(screen.getByRole('dialog')).getAllByText('—').length).toBeGreaterThan(5);
  });

  it('SRS-G-41: a selected row stays selected; another row can be chosen for its History', async () => {
    const calls = backend({
      'GET /v1/masters/circulars/current': () => [],
      'GET /v1/masters/operations/T2P/versions': () => paged([]),
      'GET /v1/masters/operations': () =>
        paged([
          { code: 'W2P', current: op(), pending: null },
          { code: 'T2P', current: op({ name: 'Truck-to-Platform' }), pending: null },
        ]),
    });
    wrap(<Operations />, ['M-27:VIEW']);
    await userEvent.click(await screen.findByText('Truck-to-Platform'));
    expect(screen.getByText('T2P').closest('tr')?.getAttribute('aria-selected')).toBe('true');
    await userEvent.click(screen.getByRole('button', { name: t('wf.historyAct') }));
    await waitFor(() => expect(calls.some((c) => c.url.includes('/operations/T2P/versions'))).toBe(true));
  });
});
