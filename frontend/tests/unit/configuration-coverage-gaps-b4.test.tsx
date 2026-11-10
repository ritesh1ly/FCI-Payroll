import { cleanup, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { GlMapping, OtherParameters, SpecialRates } from '@/modules/configuration';
import { mastersApi } from '@/modules/configuration/api/mastersApi';
import { date } from '@/shared/ui';
import { backend, me, paged, referenceLists, t, wrap } from './support/harness';

/**
 * M-22 Special Rate Tables, M-23 Other Parameters and M-24 Accounting and Bank Mapping as drawn (SRS-11-15,
 * SRS-G-7, SRS-G-8, SRS-G-38; OD-121, OD-135): every drawn button opens the closed register, the View of each row
 * with its version in force or pending, the checker's tick into the inbox and the reasons on the disabled Edits.
 */

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const REFERENCE = referenceLists({
  RATE_MASTER_PATTERN: [
    ['IDA', 'IDA pattern'],
    ['CDA', 'CDA pattern'],
  ],
  RATE_MASTER_GROUP: [
    ['SPECIAL_COMPENSATORY', 'Special Compensatory'],
    ['OTHER_RATE_MASTER', 'Other rate masters'],
  ],
});

const VERSION = {
  id: 1,
  versionNo: 1,
  effectiveFrom: '2026-04-01',
  effectiveTo: null,
  state: 'ACTIVE',
  approvalId: 1,
  circularOrderId: null,
  lines: [
    {
      designationId: null,
      categoryCode: null,
      payPattern: null,
      basicSlabFrom: null,
      basicSlabTo: null,
      cityClass: null,
      rateCode: 'HILL',
      rateValue: 1500,
      unit: 'INR',
    },
  ],
};

const special = (code: string, group: string, over = {}) => ({
  code,
  name: code,
  group,
  payCode: null,
  current: null,
  pending: null,
  ...over,
});

const assignSpy = () => {
  const assign = vi.fn();
  vi.stubGlobal('location', { ...window.location, assign });
  return assign;
};

// ---------------------------------------------------------------- M-22

describe('M-22 special rate tables', () => {
  function routes() {
    return backend({
      'GET /v1/reference/lists': () => REFERENCE,
      'GET /v1/masters/special-rates': (url: string) =>
        url.includes('OTHER_RATE_MASTER')
          ? paged([
              special('GIS_IDA', 'OTHER_RATE_MASTER', { current: VERSION }),
              special('UNIFORM_IDA', 'OTHER_RATE_MASTER'),
            ])
          : paged([
              special('HILL_IDA', 'SPECIAL_COMPENSATORY', { payCode: 163, current: VERSION }),
              special('TRIBAL_IDA', 'SPECIAL_COMPENSATORY', {
                current: VERSION,
                pending: { ...VERSION, id: 2, versionNo: 2, effectiveFrom: '2027-01-01' },
              }),
            ]),
      'GET /v1/masters/circulars/current': () => [],
    });
  }

  const register = () => screen.getByTestId('m22-register');

  it('SRS_11_15_everyDrawnButtonOfTheAdministratorOpensTheClosedRegister', async () => {
    routes();
    wrap(<SpecialRates />, ['M-22:VIEW', 'M-22:CREATE']);
    await screen.findByText('163');
    const buttons = [
      () => screen.getByRole('button', { name: t('wf.historyAct') }),
      () => screen.getByRole('button', { name: t('m04.newVersion') }),
      () => screen.getAllByRole('button', { name: t('mst.history') })[0] as HTMLElement,
      () => screen.getAllByRole('button', { name: t('mst.history') })[1] as HTMLElement,
      () => screen.getByRole('button', { name: t('m22.addVersion') }),
    ];
    for (const button of buttons) {
      await userEvent.click(button());
      await waitFor(() => expect(register().hasAttribute('open')).toBe(true));
      await userEvent.click(within(register()).getByText(t('mst.register.M-22')));
      await waitFor(() => expect(register().hasAttribute('open')).toBe(false));
    }
  });

  it('SRS_11_15_aSeededRowShowsSeeded_aPendingRowsEditIsOffWithThePendingReason', async () => {
    routes();
    wrap(<SpecialRates />, ['M-22:VIEW', 'M-22:CREATE']);
    await screen.findByText('163');
    expect(screen.getAllByText(t('m22.seeded')).length).toBe(3);
    expect(screen.getByText(t('m22.gap.UNIFORM'))).toHaveClass('p-bad');
    const edit = screen.getByRole('button', { name: `${t('common.edit')} TRIBAL_IDA` });
    expect(edit).toBeDisabled();
    expect(edit).toHaveAttribute('data-why', t('mst.why.pending'));
  });

  it('SRS_11_15_aViewerSeesAddVersionExplainedAndEditOffWithTheAdministratorReason', async () => {
    routes();
    wrap(<SpecialRates />, ['M-22:VIEW']);
    await screen.findByText('163');
    expect(screen.getByRole('button', { name: t('m22.addVersion') })).toHaveAttribute(
      'data-guard',
      t('m22.why.adminOnly'),
    );
    expect(screen.getByRole('button', { name: `${t('common.edit')} HILL_IDA` })).toHaveAttribute(
      'data-why',
      t('m04.why.adminOnly'),
    );
  });

  it('OD_135_theSystemAdministratorEditOpensTheRegisterWithoutTheEditor', async () => {
    routes();
    wrap(<SpecialRates />, me(['M-22:VIEW'], { roles: ['SYSTEM_ADMINISTRATOR'] }));
    await userEvent.click(await screen.findByRole('button', { name: `${t('common.edit')} HILL_IDA` }));
    await waitFor(() => expect(register().hasAttribute('open')).toBe(true));
    expect(screen.queryByText(t('mst.newVersionOf', ['HILL_IDA']))).toBeNull();
  });

  it('SRS_G_38_theViewShowsTheVersionInForceAndTheOnePending', async () => {
    routes();
    wrap(<SpecialRates />, ['M-22:VIEW']);
    await userEvent.click(await screen.findByRole('button', { name: `${t('common.view')} TRIBAL_IDA` }));
    let dialog = await screen.findByRole('dialog', { name: t('wf.viewHead', [t('screen.M-22')]) });
    expect(within(dialog).getByText(t('m22.carriedForward'))).toBeTruthy();
    expect(within(dialog).getByText(t('mst.pendingVersion', [2, date('2027-01-01')]))).toBeTruthy();
    await userEvent.click(within(dialog).getByRole('button', { name: t('common.close') }));
    await userEvent.click(screen.getByRole('button', { name: `${t('common.view')} UNIFORM_IDA` }));
    dialog = await screen.findByRole('dialog', { name: t('wf.viewHead', [t('screen.M-22')]) });
    expect(within(dialog).getByText(t('mst.notSeeded'))).toBeTruthy();
    expect(within(dialog).getAllByText('—')).toHaveLength(2);
    await userEvent.click(within(dialog).getByRole('button', { name: t('common.close') }));
    await userEvent.click(screen.getByRole('button', { name: `${t('common.view')} HILL_IDA` }));
    dialog = await screen.findByRole('dialog', { name: t('wf.viewHead', [t('screen.M-22')]) });
    expect(within(dialog).getByText('163')).toBeTruthy();
  });

  it('M08_08_theCheckerTickGoesToTheInboxForAPendingMasterOnly', async () => {
    routes();
    const assign = assignSpy();
    wrap(<SpecialRates />, ['M-22:VIEW', 'M-22:APPROVE']);
    await screen.findByText('163');
    const row = (name: string) =>
      screen.getByRole('button', { name: `${t('common.view')} ${name}` }).closest('tr') as HTMLElement;
    await userEvent.click(within(row('HILL_IDA')).getByTitle(t('wf.approveTitle')));
    expect(await screen.findByText(t('mst.nothingWaiting'))).toBeTruthy();
    await userEvent.click(within(row('TRIBAL_IDA')).getByTitle(t('wf.approveTitle')));
    expect(assign).toHaveBeenCalledTimes(1);
  });

  it('SRS_11_15_theOtherRateMastersFilterHidesTheSpecialTable', async () => {
    routes();
    wrap(<SpecialRates />, ['M-22:VIEW']);
    await screen.findByRole('option', { name: 'Other rate masters' });
    await userEvent.selectOptions(screen.getByLabelText(t('m22.filter.group')), 'OTHER_RATE_MASTER');
    await waitFor(() => expect(screen.queryByText(t('m22.special.title'))).toBeNull());
    expect(screen.getByText(t('m22.further.title'))).toBeTruthy();
  });

  it('SRS_G_13_anExportHolderGetsTheDownloadOfTheFilteredList', async () => {
    routes();
    wrap(<SpecialRates />, ['M-22:VIEW', 'M-22:EXPORT']);
    await screen.findByText('163');
    expect(screen.queryByText(t('m22.why.export'))).toBeNull();
    expect(screen.getByRole('button', { name: t('wf.export') })).not.toHaveAttribute('data-guard');
  });
});

// ---------------------------------------------------------------- M-23

const value = {
  id: 1,
  versionNo: 1,
  effectiveFrom: '2026-04-01',
  effectiveTo: null,
  numericValue: null,
  ceilingValue: 3000,
  percentValue: null,
  windowFromTime: null,
  windowToTime: null,
  instalmentCount: null,
  payrollTypeScope: null,
  confirmed: true,
  status: 'APPROVED',
  approvalId: 1,
};
const param = (code: string, over = {}) => ({
  code,
  name: code,
  payCode: null,
  current: null,
  pending: null,
  ...over,
});

describe('M-23 other parameters', () => {
  function routes() {
    return backend({
      'GET /v1/masters/other-parameters': () =>
        paged([
          param('OTA_RATE', { current: { ...value, ceilingValue: null, numericValue: 125 } }),
          param('OTA_CEILING', { current: value, pending: { ...value, id: 2, versionNo: 2 } }),
          param('BENEVOLENT_FUND', { current: { ...value, ceilingValue: 50 } }),
          param('PLI_CEILING', { pending: { ...value, id: 3 } }),
          param('MHS_CONTRIBUTION', { hrmsOwned: true, payCode: 371 }),
        ]),
      'GET /v1/masters/circulars/current': () => [],
    });
  }
  const register = () => screen.getByTestId('m23-register');
  const rowOf = (code: string) =>
    screen.getByRole('button', { name: `${t('common.view')} ${code}` }).closest('tr') as HTMLElement;

  it('SRS_G_8_theDrawnButtonsAndTheRowEditOpenTheRegister', async () => {
    routes();
    wrap(<OtherParameters />, ['M-23:VIEW', 'M-23:CREATE']);
    await screen.findByText(t('m23.name.OTA_RATE'));
    const outside = () =>
      screen.getAllByRole('button', { name: t('mst.history') }).filter((b) => !register().contains(b));
    const history = outside().length;
    const drawn = [
      ...Array.from({ length: history }, (_, i) => () => outside()[i] as HTMLElement),
      () => screen.getByRole('button', { name: t('wf.historyAct') }),
      () => screen.getByRole('button', { name: t('m23.newVersion') }),
      () => screen.getByRole('button', { name: `${t('common.edit')} OTA_RATE` }),
    ];
    expect(drawn.length).toBeGreaterThanOrEqual(3);
    for (const button of drawn) {
      await userEvent.click(button());
      await waitFor(() => expect(register().hasAttribute('open')).toBe(true));
      await userEvent.click(within(register()).getByText(t('mst.register.M-23')));
      await waitFor(() => expect(register().hasAttribute('open')).toBe(false));
    }
  });

  it('SRS_G_8_eachBasisHasItsPill_aPendingParameterIsOffWithTheReason', async () => {
    routes();
    wrap(<OtherParameters />, ['M-23:VIEW', 'M-23:CREATE']);
    await screen.findByText(t('m23.name.OTA_RATE'));
    expect(within(rowOf('BENEVOLENT_FUND')).getByText(t('m23.basis.BENEVOLENT_FUND'))).toHaveClass('p-warn');
    expect(within(rowOf('MHS_CONTRIBUTION')).getByText(t('m23.basis.MHS_CONTRIBUTION'))).toHaveClass(
      'p-info',
    );
    expect(within(rowOf('OTA_RATE')).getByText(t('m22.carriedForward'))).toHaveClass('p-ok');
    const pli = screen.getByRole('button', { name: `${t('common.edit')} PLI_CEILING` });
    expect(pli).toBeDisabled();
    expect(pli).toHaveAttribute('data-why', t('mst.why.pending'));
  });

  it('SRS_G_8_aViewerIsToldTheEditIsTheAdministrators', async () => {
    routes();
    wrap(<OtherParameters />, ['M-23:VIEW']);
    await screen.findByText(t('m23.name.OTA_RATE'));
    expect(screen.getByRole('button', { name: `${t('common.edit')} OTA_RATE` })).toHaveAttribute(
      'data-why',
      t('m04.why.adminOnly'),
    );
    expect(screen.getByRole('button', { name: t('m23.newVersion') })).toBeDisabled();
  });

  it('OD_135_theSystemAdministratorOpensTheRegisterFromEditWithoutTheEditor', async () => {
    routes();
    wrap(<OtherParameters />, me(['M-23:VIEW'], { roles: ['SYSTEM_ADMINISTRATOR'] }));
    await userEvent.click(await screen.findByRole('button', { name: `${t('common.edit')} OTA_RATE` }));
    await waitFor(() => expect(register().hasAttribute('open')).toBe(true));
  });

  it('SRS_G_8_theViewShowsTheValueAndDate_theHrmsRowHasNoHistory', async () => {
    routes();
    wrap(<OtherParameters />, ['M-23:VIEW']);
    await userEvent.click(await screen.findByRole('button', { name: `${t('common.view')} BENEVOLENT_FUND` }));
    let dialog = await screen.findByRole('dialog', { name: t('wf.viewHead', [t('screen.M-23')]) });
    expect(within(dialog).getByText(/≤ ₹ 50/)).toBeTruthy();
    expect(within(dialog).getByText(date('2026-04-01'))).toBeTruthy();
    await userEvent.click(within(dialog).getByRole('button', { name: t('common.close') }));
    await userEvent.click(screen.getByRole('button', { name: `${t('common.view')} MHS_CONTRIBUTION` }));
    dialog = await screen.findByRole('dialog', { name: t('wf.viewHead', [t('screen.M-23')]) });
    expect(within(dialog).getByText(t('m23.rule.MHS_CONTRIBUTION'))).toBeTruthy();
    expect(within(dialog).getByText('—')).toBeTruthy();
    expect(within(dialog).queryByRole('button', { name: /history/i })).toBeNull();
  });

  it('M08_08_theCheckerTickReadsTheRateOrItsCeilingPending', async () => {
    routes();
    const assign = assignSpy();
    wrap(<OtherParameters />, ['M-23:VIEW', 'M-23:APPROVE']);
    await screen.findByText(t('m23.name.OTA_RATE'));
    // The ceiling drawn on the rate's row is pending: the tick goes to the inbox.
    await userEvent.click(within(rowOf('OTA_RATE')).getByTitle(t('wf.approveTitle')));
    expect(assign).toHaveBeenCalledTimes(1);
    await userEvent.click(within(rowOf('BENEVOLENT_FUND')).getByTitle(t('wf.approveTitle')));
    expect(await screen.findByText(t('mst.nothingWaiting'))).toBeTruthy();
    expect(assign).toHaveBeenCalledTimes(1);
  });
});

// ---------------------------------------------------------------- M-24

describe('M-24 accounting and bank mapping', () => {
  const masters = [
    { code: 'NATURAL_ACCOUNT', effectiveFrom: '2026-04-01', records: 3, pending: true },
    { code: 'BANK', effectiveFrom: null, records: 4, pending: false },
  ];
  function routes() {
    return backend({
      'GET /v1/masters/accounting': () => paged(masters),
      'GET /v1/masters/accounting/BANK/records': () =>
        paged([
          {
            key: 'SBIN0DEMO01',
            name: null,
            detail: null,
            effectiveFrom: '2026-04-01',
            effectiveTo: '2027-03-31',
          },
        ]),
      'GET /v1/masters/gl-mappings': () => paged([]),
    });
  }
  const register = () => screen.getByTestId('m24-register');

  it('SRS_G_7_financeOpensTheRegisterFromEveryDrawnButtonAndTheNaturalAccountsRow', async () => {
    routes();
    wrap(<GlMapping />, ['M-24:VIEW', 'M-24:CREATE']);
    await screen.findByText(t('m24.master.BANK'));
    const natural = t('m24.master.NATURAL_ACCOUNT');
    const outside = () =>
      screen.getAllByRole('button', { name: t('mst.history') }).filter((b) => !register().contains(b));
    const history = outside().length;
    const drawn = [
      ...Array.from({ length: history }, (_, i) => () => outside()[i] as HTMLElement),
      () => screen.getByRole('button', { name: t('wf.historyAct') }),
      () => screen.getByRole('button', { name: t('m24.newMapping') }),
      () => screen.getByRole('button', { name: `${t('common.edit')} ${natural}` }),
      () => screen.getByRole('button', { name: `${t('common.view')} ${natural}` }),
    ];
    for (const button of drawn) {
      await userEvent.click(button());
      await waitFor(() => expect(register().hasAttribute('open')).toBe(true));
      await userEvent.click(within(register()).getByText(t('mst.register.M-24')));
      await waitFor(() => expect(register().hasAttribute('open')).toBe(false));
    }
    expect(screen.getByText(date('2026-04-01'))).toBeTruthy();
  });

  it('SRS_G_7_aMastersRecordsShowDashesForWhatTheyLackAndTheirDates', async () => {
    routes();
    wrap(<GlMapping />, ['M-24:VIEW']);
    await userEvent.click(
      await screen.findByRole('button', { name: `${t('common.view')} ${t('m24.master.BANK')}` }),
    );
    const row = (await screen.findByText('SBIN0DEMO01')).closest('tr') as HTMLElement;
    expect(within(row).getAllByText('—')).toHaveLength(2);
    expect(row).toHaveTextContent(date('2027-03-31'));
  });

  it('M08_08_theCheckerTickOnAPendingMasterGoesToTheInbox', async () => {
    routes();
    const assign = assignSpy();
    wrap(<GlMapping />, ['M-24:VIEW', 'M-24:APPROVE']);
    await screen.findByText(t('m24.master.BANK'));
    const natural = screen
      .getByRole('button', { name: `${t('common.view')} ${t('m24.master.NATURAL_ACCOUNT')}` })
      .closest('tr') as HTMLElement;
    await userEvent.click(within(natural).getByTitle(t('wf.approveTitle')));
    expect(assign).toHaveBeenCalledWith(expect.stringContaining('W-01'));
  });
});

describe('M-24 GL register versions', () => {
  it('SRS_G_7_aVersionStillInForceHasNoEndDate', async () => {
    backend({
      'GET /v1/masters/accounting': () => paged([]),
      'GET /v1/masters/gl-mappings': () =>
        paged([
          {
            payCode: 101,
            name: 'Basic Pay',
            type: 'EARNING',
            payslipGroup: null,
            retired: false,
            current: {
              id: 1,
              versionNo: 1,
              effectiveFrom: '2026-04-01',
              effectiveTo: null,
              naturalAccount: '401-100',
              status: 'APPROVED',
              approvalId: 1,
            },
            pending: null,
          },
        ]),
      'GET /v1/masters/gl-mappings/101/versions': () =>
        paged([
          {
            id: 1,
            versionNo: 1,
            effectiveFrom: '2026-04-01',
            effectiveTo: null,
            naturalAccount: '401-100',
            status: 'APPROVED',
            approvalId: 1,
          },
        ]),
    });
    wrap(<GlMapping />, ['M-24:VIEW']);
    await screen.findByText('Basic Pay');
    await userEvent.click(
      within(screen.getByTestId('m24-register')).getAllByRole('button', {
        name: t('mst.history'),
      })[0] as HTMLElement,
    );
    const status = await screen.findByText(t('status.APPROVED'));
    const row = status.closest('tr') as HTMLElement;
    expect(row).toHaveTextContent('v1');
    expect(within(row).getByText('—')).toBeTruthy();
  });
});

describe('masters API query strings (SRS-11-15, SRS-G-30)', () => {
  it('SRS_11_15_theSpecialRateFiltersReachTheListAndTheExport_emptyOnesAreLeftOut', async () => {
    const calls = backend({ 'GET /v1/masters/special-rates': () => paged([]) });
    await mastersApi.specialRates({ pattern: '', group: '', q: '  ' }, 0, 20);
    await mastersApi.specialRates({ pattern: 'CDA', group: 'OTHER_RATE_MASTER', q: ' hill ' }, 1, 50);
    expect(calls.map((c) => c.url.replace(/^.*\/v1/, '/v1'))).toEqual([
      '/v1/masters/special-rates?page=0&size=20',
      '/v1/masters/special-rates?pattern=CDA&group=OTHER_RATE_MASTER&q=hill&page=1&size=50',
    ]);
    expect(mastersApi.specialRatesExportUrl({ pattern: '', group: '', q: '' })).toMatch(
      /\/v1\/masters\/special-rates\/export$/,
    );
    expect(mastersApi.specialRatesExportUrl({ pattern: 'IDA', group: '', q: '' })).toMatch(
      /\/export\?pattern=IDA$/,
    );
  });

  it('SRS_G_30_anOrderWithoutReferenceOrAuthoritySendsNeitherField', async () => {
    const calls = backend({
      'POST /v1/masters/staff-rates/versions/7/order': () => ({ status: 201, body: {} }),
    });
    const file = new File(['%PDF-1.7'], 'o.pdf', { type: 'application/pdf' });
    await mastersApi.attachStaffRateOrder(7, {
      documentType: 'CIRCULAR',
      reference: '',
      orderDate: '2026-01-02',
      issuingAuthority: '',
      file,
    });
    await mastersApi.attachStaffRateOrder(7, {
      documentType: 'CIRCULAR',
      reference: 'HQ/1',
      orderDate: '2026-01-02',
      issuingAuthority: 'HQ',
      file,
    });
    const [bare, full] = calls.map((c) => c.body as FormData);
    expect(bare?.has('reference')).toBe(false);
    expect(bare?.has('issuingAuthority')).toBe(false);
    expect(bare?.get('orderDate')).toBe('2026-01-02');
    expect(full?.get('reference')).toBe('HQ/1');
    expect(full?.get('issuingAuthority')).toBe('HQ');
  });
});
