import { cleanup, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { GlMapping, OtherParameters, SpecialRates, StaffRates } from '@/modules/configuration';
import {
  arrearImpact,
  daFormProblem,
  drawnParameterRows,
  masterBase,
  versionTone,
} from '@/modules/configuration/business/masterRules';
import { backend, me, paged, referenceLists, sent, t, wrap } from './support/harness';

/**
 * M-04, M-22, M-23 and M-24 as Wireframes v6.1 draws them (OD-121; SRS-1-62, SRS-11-15, SRS-G-7): the
 * drawn objects per role, disabled actions with their reason, the SRS fields asked at submission and the
 * registers kept behind their closed sections.
 */
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const REFERENCE = referenceLists({
  DA_RATE_PATTERN: [
    ['IDA', 'IDA'],
    ['CDA', 'CDA'],
  ],
  RATE_MASTER_PATTERN: [
    ['IDA', 'IDA pattern'],
    ['CDA', 'CDA pattern'],
  ],
  RATE_MASTER_GROUP: [
    ['SPECIAL_COMPENSATORY', 'Special Compensatory'],
    ['OTHER_RATE_MASTER', 'Other rate masters'],
  ],
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

function staffRoutes() {
  return backend({
    'GET /v1/reference/lists': () => REFERENCE,
    'GET /v1/masters/staff-rates': () => paged([]),
    'GET /v1/masters/staff-rates/dearness-versions': () =>
      paged([
        DA,
        {
          ...DA,
          id: 8,
          versionNo: 2,
          rate: 53,
          computedOn: 'Basic + Stagnation',
          arrearReference: 'AE-131',
          arrearAmount: 536000,
          status: 'AWAITING_APPROVAL',
        },
        { ...DA, id: 6, versionNo: 0, effectiveTo: '2024-09-30', status: 'SUPERSEDED' },
      ]),
    'GET /v1/masters/staff-rates/city-classes': () =>
      paged([
        { cityClass: 'X', hraPercent: 30, hraCdaPercent: 28, ccaAmount: 1800, inForce: true, pending: true },
        { cityClass: 'Y', hraPercent: 20, hraCdaPercent: 20, ccaAmount: 1200, inForce: true, pending: false },
        {
          cityClass: 'Z',
          hraPercent: null,
          hraCdaPercent: null,
          ccaAmount: null,
          inForce: false,
          pending: false,
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
    'GET /v1/masters/staff-rates/versions/7/orders': () => [],
    'GET /v1/document-types': () => [{ code: 'CIRCULAR', referenceRequired: true }],
    'GET /v1/masters/circulars/current': () => [{ id: 5, number: 'HQ/DA/5', subject: 'DA revision' }],
    'POST /v1/masters/staff-rates/': () => ({ status: 202, body: {} }),
  });
}

describe('M-04 Staff Rate Tables as drawn', () => {
  it('SRS-1-62: a viewer sees the note, New Rate Version locked with the reason and the three versions', async () => {
    staffRoutes();
    wrap(<StaffRates />, ['M-04:VIEW']);
    expect(await screen.findByText(t('m04.form.adminOnly'))).toBeTruthy();
    const locked = screen.getByRole('button', { name: t('m04.newVersion') });
    expect(locked).toBeDisabled();
    expect(locked).toHaveAttribute('data-why', t('m04.why.adminOnly'));
    await screen.findByText('v2');
    expect(screen.getByText('AE-131 · ₹ 5,36,000')).toBeTruthy();
    expect(screen.getByText('Basic + Stagnation')).toBeTruthy();
    expect(screen.getAllByText(t('m04.computedOn.IDA')).length).toBeGreaterThan(0);
    expect(screen.queryByLabelText(/Rate \(%\)/)).toBeNull();
  });

  it('a superseded version is immutable; a version awaiting approval says so', async () => {
    staffRoutes();
    wrap(<StaffRates />, ['M-04:VIEW', 'M-04:CREATE']);
    const off = await screen.findByRole('button', { name: `${t('common.edit')} DA_IDA v0` });
    expect(off).toBeDisabled();
    expect(off).toHaveAttribute('data-why', t('m04.why.superseded'));
    expect(screen.getByRole('button', { name: `${t('common.edit')} DA_IDA v2` })).toHaveAttribute(
      'data-why',
      t('m04.why.awaiting'),
    );
    expect(screen.getByRole('button', { name: `${t('common.edit')} DA_IDA v1` })).toBeEnabled();
  });

  it('SRS-EH-3: M-04 marks the field that failed, so the reason is visible beside it, and clears it once fixed', async () => {
    staffRoutes();
    wrap(<StaffRates />, ['M-04:VIEW', 'M-04:CREATE']);
    await screen.findByText('v2');
    const invalid = () => document.querySelectorAll('.rf-field.rf-invalid');
    expect(invalid()).toHaveLength(0);
    const save = screen.getByRole('button', { name: t('m04.form.save') });
    await userEvent.click(save);
    expect(invalid()).toHaveLength(1);
    expect(invalid()[0]).toHaveTextContent(t('m04.err.pattern'));
    await screen.findByRole('option', { name: 'CDA' });
    await userEvent.selectOptions(screen.getByLabelText(/Pattern/), 'CDA');
    await userEvent.type(screen.getByLabelText(/Rate \(%\)/), '101');
    await userEvent.click(save);
    expect(invalid()).toHaveLength(1);
    expect(invalid()[0]).toHaveTextContent(t('m04.err.rate'));
  });

  it('SRS-1-62: Save validates as drawn, asks the circular and reason, then proposes a DA line with its base', async () => {
    const calls = staffRoutes();
    wrap(<StaffRates />, ['M-04:VIEW', 'M-04:CREATE']);
    await screen.findByText('v2');
    const save = screen.getByRole('button', { name: t('m04.form.save') });
    await userEvent.click(save);
    expect(screen.getByText(t('m04.err.pattern'))).toBeTruthy();
    await screen.findByRole('option', { name: 'CDA' });
    await userEvent.selectOptions(screen.getByLabelText(/Pattern/), 'CDA');
    await userEvent.type(screen.getByLabelText(/Rate \(%\)/), '48');
    await userEvent.type(screen.getByLabelText(/Computed On/), 'Basic + NPA');
    await userEvent.type(screen.getByLabelText(/Effective From/), '2027-01-01');
    await userEvent.click(save);
    const dialog = within(await screen.findByRole('dialog'));
    const submit = dialog.getByRole('button', { name: t('common.submitForApproval') });
    expect(submit).toBeDisabled();
    expect(submit).toHaveAttribute('title', t('mst.why.circular'));
    await dialog.findByRole('option', { name: 'HQ/DA/5 · DA revision' });
    await userEvent.selectOptions(dialog.getByLabelText(t('mst.circular')), '5');
    await userEvent.type(dialog.getByLabelText(t('common.reason')), 'DA revision');
    await userEvent.click(submit);
    await waitFor(() =>
      expect(sent(calls, 'POST', '/v1/masters/staff-rates/DA_CDA/versions')[0]).toMatchObject({
        effectiveFrom: '2027-01-01',
        computedOn: 'Basic + NPA',
        circularOrderId: 5,
        lines: [{ payPattern: 'CDA', rateCode: 'DA_PCT', rateValue: 48, unit: 'PERCENT' }],
      }),
    );
  });

  it('WF-208: the System Administrator sees the form as drawn and is told why it cannot save', async () => {
    staffRoutes();
    wrap(<StaffRates />, me(['M-04:VIEW'], { roles: ['SYSTEM_ADMINISTRATOR'] }));
    await screen.findByLabelText(/Rate \(%\)/);
    await userEvent.click(screen.getByRole('button', { name: t('m04.form.save') }));
    expect(await screen.findByText(t('mst.why.notMaker'))).toBeTruthy();
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('the city table: one figure where the patterns agree, both where they differ, Double HRA its rule', async () => {
    staffRoutes();
    wrap(<StaffRates />, ['M-04:VIEW', 'M-04:APPROVE']);
    expect(await screen.findByText(t('m04.hra.both', ['30%', '28%']))).toBeTruthy();
    expect(screen.getByText('20%')).toBeTruthy();
    expect(screen.getByText(t('m04.hra.double'))).toBeTruthy();
    expect(screen.getByText(t('m04.suppression.double'))).toBeTruthy();
    expect(screen.getAllByText(t('mst.notSeeded')).length).toBeGreaterThan(0);
    const row = screen.getByRole('button', { name: `${t('common.view')} Z` });
    await userEvent.click(row);
    expect(await screen.findByRole('dialog')).toBeTruthy();
  });
});

const special = (code: string, group: string, over = {}) => ({
  code,
  name: code,
  group,
  payCode: null,
  current: null,
  pending: null,
  ...over,
});

describe('M-22 rate masters as drawn', () => {
  function routes() {
    return backend({
      'GET /v1/reference/lists': () => REFERENCE,
      'GET /v1/masters/special-rates': (url: string) =>
        url.includes('OTHER_RATE_MASTER')
          ? paged([special('GIS_IDA', 'OTHER_RATE_MASTER')])
          : paged([special('HILL_IDA', 'SPECIAL_COMPENSATORY', { payCode: 163 })]),
      'GET /v1/masters/circulars/current': () => [],
    });
  }

  it('SRS-11-15: the filters reach the server; a master without a rate says so; Export is explained', async () => {
    const calls = routes();
    wrap(<SpecialRates />, ['M-22:VIEW']);
    expect(await screen.findByText('Hill Compensatory Allowance')).toBeTruthy();
    expect(screen.getByText(t('m22.gap.GIS'))).toBeTruthy();
    await screen.findByRole('option', { name: 'CDA pattern' });
    await userEvent.selectOptions(screen.getByLabelText(t('m22.filter.pattern')), 'CDA');
    await waitFor(() => expect(calls.some((c) => c.url.includes('pattern=CDA'))).toBe(true));
    await userEvent.selectOptions(screen.getByLabelText(t('m22.filter.group')), 'SPECIAL_COMPENSATORY');
    await waitFor(() => expect(screen.queryByText(t('m22.further.title'))).toBeNull());
    await userEvent.click(screen.getByRole('button', { name: t('wf.export') }));
    expect(await screen.findByText(t('m22.why.export'))).toBeTruthy();
    const locked = screen.getByRole('button', { name: t('m04.newVersion') });
    expect(locked).toHaveAttribute('data-why', t('m22.why.adminOnly'));
  });

  it('SRS-G-38: Edit of a row opens the version editor of that master', async () => {
    routes();
    wrap(<SpecialRates />, ['M-22:VIEW', 'M-22:CREATE']);
    await userEvent.click(await screen.findByRole('button', { name: `${t('common.edit')} HILL_IDA` }));
    expect(await screen.findByText(t('mst.newVersionOf', ['HILL_IDA']))).toBeTruthy();
  });
});

const param = (code: string, over = {}) => ({
  code,
  name: code,
  payCode: null,
  current: null,
  pending: null,
  ...over,
});
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

describe('M-23 Other Parameters as drawn', () => {
  it('SRS-G-8: a rate and its ceiling read as one row; the HRMS row holds no value and no Edit', async () => {
    backend({
      'GET /v1/masters/other-parameters': () =>
        paged([
          param('OTA_RATE', {
            current: { ...value, ceilingValue: null, numericValue: 125, confirmed: false },
          }),
          param('OTA_CEILING', { current: value }),
          param('PLI_CEILING'),
          param('MHS_CONTRIBUTION', { hrmsOwned: true, payCode: 371 }),
        ]),
    });
    wrap(<OtherParameters />, ['M-23:VIEW', 'M-23:CREATE']);
    expect(await screen.findByText(t('m23.name.OTA_RATE'))).toBeTruthy();
    expect(screen.queryByText(t('m23.name.OTA_CEILING'))).toBeNull();
    expect(screen.getByText(/125 · ≤ ₹ 3,000/)).toBeTruthy();
    expect(screen.getByText(t('m23.rule.MHS_CONTRIBUTION'))).toBeTruthy();
    const edit = screen.getByRole('button', { name: `${t('common.edit')} MHS_CONTRIBUTION` });
    expect(edit).toBeDisabled();
    expect(edit).toHaveAttribute('data-why', t('m23.why.hrms'));
  });
});

describe('M-24 Accounting & Bank Mapping as drawn', () => {
  const masters = [
    { code: 'NATURAL_ACCOUNT', effectiveFrom: '2026-04-01', records: 3, pending: true },
    { code: 'BANK', effectiveFrom: null, records: 4, pending: false },
    { code: 'AGENCY', effectiveFrom: null, records: 0, pending: false },
  ];
  function routes() {
    return backend({
      'GET /v1/masters/accounting': () => paged(masters),
      'GET /v1/masters/accounting/BANK/records': () =>
        paged([
          {
            key: 'SBIN0DEMO01',
            name: 'Okhla',
            detail: 'State Bank of India',
            effectiveFrom: null,
            effectiveTo: null,
          },
        ]),
      'GET /v1/masters/gl-mappings': () => paged([]),
    });
  }

  it('SRS-G-7: Finance has New Mapping and Edit of the natural accounts; other roles the reason', async () => {
    routes();
    wrap(<GlMapping />, ['M-24:VIEW', 'M-24:CREATE']);
    expect(
      await screen.findByRole('button', { name: `${t('common.edit')} ${t('m24.master.NATURAL_ACCOUNT')}` }),
    ).toBeEnabled();
    expect(screen.getByRole('button', { name: t('m24.newMapping') })).toBeEnabled();
    const bank = screen.getByRole('button', { name: `${t('common.edit')} ${t('m24.master.BANK')}` });
    expect(bank).toBeDisabled();
    expect(bank).toHaveAttribute('data-why', t('m24.why.BANK'));
  });

  it('SRS-G-7: the Administrator sees New Mapping locked; a master without records is not seeded', async () => {
    routes();
    wrap(<GlMapping />, ['M-24:VIEW']);
    const locked = await screen.findByRole('button', { name: t('m24.newMapping') });
    expect(locked).toBeDisabled();
    expect(locked).toHaveAttribute('data-why', t('m24.why.financeMaker'));
    expect(await screen.findByText(t('mst.notSeeded'))).toBeTruthy();
  });

  it('View opens the records of a master, read only, and closes', async () => {
    routes();
    wrap(<GlMapping />, ['M-24:VIEW']);
    await userEvent.click(
      await screen.findByRole('button', { name: `${t('common.view')} ${t('m24.master.BANK')}` }),
    );
    expect(await screen.findByText('SBIN0DEMO01')).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('common.close') }));
    expect(screen.queryByText('SBIN0DEMO01')).toBeNull();
  });

  it('✔ of a master with nothing waiting says so', async () => {
    routes();
    wrap(<GlMapping />, ['M-24:VIEW', 'M-24:APPROVE']);
    await screen.findByText(t('m24.master.BANK'));
    const approve = screen.getAllByRole('button', { name: '✔' });
    await userEvent.click(approve[1] as HTMLElement);
    expect(await screen.findByText(t('mst.nothingWaiting'))).toBeTruthy();
  });
});

describe('master rules', () => {
  it('daFormProblem: the first drawn error, or none', () => {
    const ok = { pattern: 'IDA', rate: '53', basis: 'Basic + NPA', from: '2027-01-01' };
    expect(daFormProblem(ok)).toBeNull();
    expect(daFormProblem({ ...ok, pattern: '' })).toBe('m04.err.pattern');
    expect(daFormProblem({ ...ok, rate: '' })).toBe('m04.err.rate');
    expect(daFormProblem({ ...ok, rate: '100.5' })).toBe('m04.err.rate');
    expect(daFormProblem({ ...ok, rate: '-1' })).toBe('m04.err.rate');
    expect(daFormProblem({ ...ok, rate: '0' })).toBeNull();
    expect(daFormProblem({ ...ok, basis: '  ' })).toBe('m04.err.basis');
    expect(daFormProblem({ ...ok, basis: 'Basic "quoted"' })).toBe('m04.err.basis');
    expect(daFormProblem({ ...ok, from: '' })).toBe('m04.err.from');
  });

  it('versionTone, masterBase, arrearImpact, drawnParameterRows', () => {
    expect(versionTone('ACTIVE')).toBe('p-ok');
    expect(versionTone('SCHEDULED')).toBe('p-warn');
    expect(versionTone('AWAITING_APPROVAL')).toBe('p-warn');
    expect(versionTone('REJECTED')).toBe('p-bad');
    expect(versionTone('SUPERSEDED')).toBe('p-gray');
    expect(masterBase('HILL_IDA')).toBe('HILL');
    expect(masterBase('MISC_ALLOWANCE_CDA')).toBe('MISC_ALLOWANCE');
    expect(masterBase('OTA_RATE')).toBe('OTA_RATE');
    expect(arrearImpact(null, 5)).toBe('—');
    expect(arrearImpact('AE-1', null)).toBe('AE-1');
    // A ceiling whose rate is on another page stays its own row.
    expect(drawnParameterRows([{ code: 'OTA_CEILING' }]).map((d) => d.row.code)).toEqual(['OTA_CEILING']);
    const pair = drawnParameterRows([{ code: 'NPA_PCT' }, { code: 'NPA_CEILING' }]);
    expect(pair).toHaveLength(1);
    expect(pair[0]?.ceiling?.code).toBe('NPA_CEILING');
  });
});
