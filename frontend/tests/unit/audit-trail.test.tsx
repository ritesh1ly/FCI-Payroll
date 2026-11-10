import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AuditTrail, HistoryButton } from '@/modules/audit';
import { isoDay } from '@/modules/audit/business/auditFilter';
import { date } from '@/shared/ui';
import { backend, exportedUrl, me, problem, t, wrap } from './support/harness';

afterEach(() => vi.unstubAllGlobals());

const ROW = {
  transactionId: 41,
  changedAt: '2026-10-21T10:00:00Z',
  objectKey: 'rule_version · OTP_VALIDITY',
  field: 'scalar_value',
  oldValue: '5',
  newValue: '10',
  outcome: 'POSTED',
  changeType: 'CHANGE',
  effectiveDate: '2026-10-21',
  reason: 'IT: longer',
  approvalId: 9,
  approvalStatus: 'APPROVED',
  maker: { loginId: 'sysadmin@fci-demo.test', name: 'Sys Admin', employeeNumber: '100001' },
  checker: { loginId: 'checker.hq@fci-demo.test', name: 'HQ Checker', employeeNumber: null },
  sourceIp: '10.0.0.1',
  moduleCode: 'A-07',
  origin: 'NATIVE',
};

function view(over: Record<string, unknown> = {}) {
  return {
    entity: 'rule_version',
    key: 'OTP_VALIDITY',
    screen: 'A-07',
    versioned: true,
    versions: [
      {
        versionNo: 2,
        effectiveFrom: '2026-10-21',
        effectiveTo: null,
        approvalId: 9,
        values: { scalar_value: '10' },
      },
      {
        versionNo: 1,
        effectiveFrom: '2026-04-01',
        effectiveTo: '2026-10-20',
        approvalId: null,
        values: { scalar_value: '5' },
      },
    ],
    items: [
      ROW,
      {
        ...ROW,
        transactionId: 40,
        field: 'DECISION',
        outcome: 'APPROVED',
        oldValue: 'PENDING_APPROVAL',
        newValue: 'APPROVED',
      },
    ],
    page: 0,
    size: 50,
    total: 2,
    ...over,
  };
}

const LEGACY = {
  entity: 'employee',
  key: '104882',
  items: [
    {
      id: 1,
      origin: 'LEGACY',
      sourceObject: 'PAY_EMP_BASIC_HIST',
      sourceKey: 'EMP_NUM=104882|EFF_DT=2016-07-01',
      grain: 'ROW_IMAGE',
      actionCode: 'I',
      fieldName: null,
      oldValue: null,
      newValue: null,
      rowImage: '{"EMP_NUM":"104882","BASIC":49500}',
      changedFields: 'BASIC',
      changedBy: 'LEGACYUSR01',
      changedAt: '2016-07-01T04:30:00Z',
      reason: null,
    },
    {
      id: 2,
      origin: 'LEGACY',
      sourceObject: 'AUDIT_PAY_EMP_MAST',
      sourceKey: 'EMP_NUM=104882|SEQ=2',
      grain: 'FIELD',
      actionCode: 'U',
      fieldName: 'BASIC_PAY',
      oldValue: '52000',
      newValue: '53600',
      rowImage: null,
      changedFields: null,
      changedBy: 'LEGACYUSR02',
      changedAt: '2019-07-01T03:35:00Z',
      reason: 'Annual increment (demo)',
    },
  ],
  page: 0,
  size: 50,
  total: 2,
};

/** The drawn Filter button opens dates, free text, the total and the read-only note. */
const openFilter = () => userEvent.click(screen.getByRole('button', { name: t('x04w.filter') }));

describe('A-05 / X-04 Audit Trail & Change History', () => {
  it('SRS-G-13: the trail search pages on the server, so every row is reachable; the count is the total', async () => {
    const calls = backend({
      'GET /v1/admin/audit': () => ({
        items: [
          {
            id: 7,
            changedAt: '2026-10-21T10:00:00Z',
            changedBy: 'sysadmin@fci-demo.test',
            entityName: 'app_user',
            entityKey: 'u1',
            fieldName: 'LOGIN',
            oldValue: null,
            newValue: 'SUCCESS',
            reason: '-',
            approvalId: null,
            moduleCode: null,
            sourceIp: null,
          },
        ],
        page: 0,
        size: 50,
        total: 731,
      }),
    });
    wrap(<AuditTrail />, ['A-05:VIEW', 'A-05:EXPORT']);
    expect((await screen.findAllByText(/LOGIN/)).length).toBeGreaterThan(0);
    await openFilter();
    // The total reaches the title from an effect after the rows render: wait for it (a synchronous
    // read raced that effect when the machine was loaded).
    expect(await screen.findByText(new RegExp(t('a05.title', [731]).replace(/[()]/g, '\\$&')))).toBeTruthy();
    const search = calls.find((c) => c.url.includes('/v1/admin/audit?'));
    expect(search?.url).toContain('page=0');
    expect(search?.url).toContain('size=20');
    expect(await exportedUrl('CSV', t('x04w.exportTrail'))).toContain('/v1/admin/audit/export?');
    // History is read only for everyone.
    expect(screen.getByText(new RegExp(t('x04.readOnly')))).toBeTruthy();
  });

  it('SRS-G-13: the filter is sent with the search and the export; a missing value shows a dash', async () => {
    const calls = backend({
      'GET /v1/admin/audit': () => ({
        items: [
          {
            id: 8,
            changedAt: '2026-10-21T10:00:00Z',
            changedBy: 'sysadmin@fci-demo.test',
            entityName: 'app_user',
            entityKey: 'u1',
            fieldName: 'STATUS',
            oldValue: 'ACTIVE',
            newValue: null,
            reason: 'IT',
            approvalId: null,
            moduleCode: 'A-02',
            sourceIp: null,
          },
        ],
        page: 0,
        size: 50,
        total: 1,
      }),
    });
    wrap(<AuditTrail />, ['A-05:VIEW', 'A-05:EXPORT']);
    expect(
      await within(await screen.findByRole('table', { name: t('x04w.trail') })).findByText('STATUS'),
    ).toBeTruthy();
    expect(screen.getAllByText('—').length).toBeGreaterThan(0);
    await openFilter();
    await userEvent.type(screen.getByRole('combobox', { name: t('a05.entity') }), 'app_user');
    await userEvent.type(screen.getByRole('textbox', { name: t('a05.actor') }), 'sysadmin');
    await userEvent.type(screen.getByRole('textbox', { name: t('a05.field') }), 'STATUS');
    await userEvent.click(screen.getByRole('button', { name: t('common.search') }));
    await waitFor(() => {
      const last = calls.filter((c) => c.url.includes('/v1/admin/audit?')).at(-1)?.url ?? '';
      expect(last).toContain('entity=app_user');
      expect(last).toContain('actor=sysadmin');
      expect(last).toContain('field=STATUS');
    });
    const exported = (await exportedUrl('CSV', t('x04w.exportTrail'))) ?? '';
    expect(exported).toContain('entity=app_user');
  });

  it('SRS-G-13: To before From is refused on the screen with its reason; one day is searched as its India day', async () => {
    const calls = backend({ 'GET /v1/admin/audit': () => ({ items: [], page: 0, size: 50, total: 0 }) });
    wrap(<AuditTrail />, ['A-05:VIEW']);
    await screen.findAllByText(t('a05.none'));
    await openFilter();
    // UI_BMAD_g1 #99: the empty result names the dates searched (yesterday and today, India).
    expect(screen.getByTestId('a05-none-range').textContent).toBe(
      t('a05.noneRange', [date(isoDay(-1)), date(isoDay(0))]),
    );
    const searches = () => calls.filter((c) => c.url.includes('/v1/admin/audit?'));
    const from = screen.getByLabelText(t('a05.from'));
    const to = screen.getByLabelText(t('a05.to'));
    fireEvent.change(from, { target: { value: '2026-10-24' } });
    fireEvent.change(to, { target: { value: '2026-10-23' } });
    const before = searches().length;
    await userEvent.click(screen.getByRole('button', { name: t('common.search') }));
    expect(await screen.findByRole('alert')).toHaveTextContent(t('a05.rangeReversed'));
    expect(searches()).toHaveLength(before);
    // From = To: that India day, midnight to midnight IST.
    fireEvent.change(from, { target: { value: '2026-10-23' } });
    await userEvent.click(screen.getByRole('button', { name: t('common.search') }));
    await waitFor(() => {
      const last = searches().at(-1)?.url ?? '';
      expect(last).toContain('from=2026-10-22T18%3A30%3A00.000Z');
      expect(last).toContain('to=2026-10-23T18%3A30%3A00.000Z');
    });
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('SC-03-0012: a record shows its versions, every change with outcome, maker and checker, and the Legacy Audit rows beside it', async () => {
    const calls = backend({
      'GET /v1/audit/record-types': () => ['employee', 'rule_version'],
      'GET /v1/audit/history?': () => view(),
      'GET /v1/audit/legacy?': () => LEGACY,
      'GET /v1/admin/audit': () => ({ items: [], page: 0, size: 50, total: 0 }),
    });
    const user = userEvent.setup();
    wrap(<AuditTrail />, ['A-05:VIEW', 'A-05:EXPORT']);
    const type = await screen.findByLabelText(t('x04.type'));
    await waitFor(() => expect(within(type).getAllByRole('option')).toHaveLength(3));
    // Nothing is asked without a type and a key.
    await user.click(screen.getByRole('button', { name: t('x04.show') }));
    expect(screen.getByRole('alert').textContent).toBe(t('x04.keyRequired'));
    await user.selectOptions(type, 'rule_version');
    await user.type(screen.getByLabelText(t('x04.key')), '  OTP_VALIDITY ');
    await user.click(screen.getByRole('button', { name: t('x04.show') }));

    // Native side: versions (newest first, the open one marked) and each change.
    expect(await screen.findByText(t('x04.versions'))).toBeTruthy();
    expect(screen.getByText(t('x04.open'))).toBeTruthy();
    expect((await screen.findAllByText('Sys Admin (100001)')).length).toBe(2);
    expect(screen.getAllByText('HQ Checker').length).toBeGreaterThan(0);
    expect(screen.getByText(t('x04.outcome.POSTED'))).toBeTruthy();
    expect(screen.getAllByText(t('x04.outcome.APPROVED')).length).toBeGreaterThan(0);
    expect(screen.getAllByText('APR-9').length).toBeGreaterThan(0);
    // The key was trimmed before it was sent.
    expect(calls.some((c) => c.url.includes('/v1/audit/history?entity=rule_version&key=OTP_VALIDITY&'))).toBe(
      true,
    );

    // Legacy side: marked legacy-origin, the snapshot shown whole, a field row as held.
    const legacy = screen.getByText(t('x04.legacy.title')).closest('.card') as HTMLElement;
    expect(within(legacy).getAllByText(t('x04.origin.LEGACY'))).toHaveLength(2);
    expect(within(legacy).getByText('BASIC: 49500')).toBeTruthy();
    expect(within(legacy).getByText('BASIC_PAY')).toBeTruthy();
    expect(within(legacy).getByText('52000 →')).toBeTruthy();
    expect(within(screen.getByTestId('x04-enquiry')).getByText(t('x04.legacy.logged'))).toBeTruthy();
  });

  it('SRS-20-6: as of a date the record shows exactly its version, or that it was not in force', async () => {
    let status = 'FOUND';
    backend({
      'GET /v1/audit/record-types': () => ['rule_version'],
      'GET /v1/audit/history/as-of': () =>
        status === 'FOUND'
          ? {
              entity: 'rule_version',
              key: 'OTP_VALIDITY',
              date: '2026-05-01',
              status: 'FOUND',
              versioned: true,
              versionNo: 1,
              effectiveFrom: '2026-04-01',
              effectiveTo: '2026-10-20',
              approvalId: null,
              values: { scalar_value: '5' },
            }
          : {
              entity: 'rule_version',
              key: 'OTP_VALIDITY',
              date: '2020-01-01',
              status: 'NOT_IN_FORCE',
              versioned: true,
              values: {},
            },
      'GET /v1/audit/history?': () => view(),
      'GET /v1/audit/legacy?': () => ({ ...LEGACY, items: [], total: 0 }),
      'GET /v1/admin/audit': () => ({ items: [], page: 0, size: 50, total: 0 }),
    });
    const user = userEvent.setup();
    wrap(<AuditTrail />, ['A-05:VIEW']);
    await waitFor(() =>
      expect(within(screen.getByLabelText(t('x04.type'))).getAllByRole('option')).toHaveLength(2),
    );
    await user.selectOptions(screen.getByLabelText(t('x04.type')), 'rule_version');
    await user.type(screen.getByLabelText(t('x04.key')), 'OTP_VALIDITY');
    await user.type(screen.getByLabelText(t('x04.asOf')), '2026-05-01');
    await user.click(screen.getByRole('button', { name: t('x04.show') }));
    expect(await screen.findByText(t('x04.asOf.found', [date('2026-05-01'), 1]))).toBeTruthy();
    expect(screen.getByText(t('x04.legacy.none'))).toBeTruthy();
    // Without EXPORT there is no report, no extract and no legacy export: the one drawn Export only says why.
    const drawn = screen.getAllByRole('button', { name: t('action.export') });
    expect(drawn).toHaveLength(1);
    expect(drawn[0]).toHaveAttribute('data-guard', t('x04w.why.export'));

    status = 'NOT_IN_FORCE';
    await user.clear(screen.getByLabelText(t('x04.asOf')));
    await user.type(screen.getByLabelText(t('x04.asOf')), '2020-01-01');
    await user.click(screen.getByRole('button', { name: t('x04.show') }));
    expect(await screen.findByText(t('x04.asOf.notInForce', [date('2020-01-01')]))).toBeTruthy();
  });

  it('SC-03-0016: a key that resolves to no record shows the server reason, not an empty history', async () => {
    backend({
      'GET /v1/audit/record-types': () => ['employee'],
      'GET /v1/audit/history?': () => problem(404, 'AUD-404-RECORD'),
      'GET /v1/audit/legacy?': () => problem(404, 'AUD-404-RECORD'),
      'GET /v1/admin/audit': () => ({ items: [], page: 0, size: 50, total: 0 }),
    });
    const user = userEvent.setup();
    wrap(<AuditTrail />, ['A-05:VIEW']);
    await waitFor(() =>
      expect(within(screen.getByLabelText(t('x04.type'))).getAllByRole('option')).toHaveLength(2),
    );
    await user.selectOptions(screen.getByLabelText(t('x04.type')), 'employee');
    await user.type(screen.getByLabelText(t('x04.key')), 'NO-SUCH');
    await user.click(screen.getByRole('button', { name: t('x04.show') }));
    expect((await screen.findAllByText(t('error.AUD-404-RECORD'))).length).toBeGreaterThan(0);
    expect(screen.queryByText(t('x04.none'))).toBeNull();
  });
});

describe('X-04 as Wireframes v6.1 draws it', () => {
  const ROW7 = {
    id: 7,
    changedAt: '2026-10-21T10:00:00Z',
    changedBy: 'maker.delhi@fci-demo.test',
    entityName: 'employee',
    entityKey: '104882',
    fieldName: 'CITY_TYPE_HRA',
    oldValue: 'Y',
    newValue: 'X',
    reason: 'City re-classification',
    approvalId: 11,
    moduleCode: 'M-01',
    sourceIp: '10.24.6.31',
    effectiveDate: '2026-01-01',
    outcome: 'POSTED',
    approvalStatus: 'APPROVED',
    maker: { loginId: 'maker.delhi@fci-demo.test', name: 'S. Rani', employeeNumber: '088412' },
    checker: { loginId: 'ddo.delhi@fci-demo.test', name: 'P. Menon', employeeNumber: null },
    order: { number: 'EST/RO-DEL/2026/301', documentId: 91 },
  };
  const value = (code: string, label: string) => ({
    code,
    label,
    labelHi: null,
    sortOrder: 1,
    numeric: null,
    parent: null,
    active: true,
  });
  const LISTS = {
    asOf: '2026-11-07',
    lists: {
      AUDIT_OBJECT_GROUP: {
        code: 'AUDIT_OBJECT_GROUP',
        name: 'x',
        module: 'AUDIT',
        system: true,
        values: [value('WORK_SLIPS', 'Work-slips')],
      },
      AUDIT_USER_GROUP: {
        code: 'AUDIT_USER_GROUP',
        name: 'y',
        module: 'AUDIT',
        system: true,
        values: [value('SYSTEM', 'System')],
      },
    },
  };
  const trail = (over: Record<string, () => unknown> = {}) =>
    backend({
      'GET /v1/reference/lists': () => LISTS,
      'GET /v1/admin/audit/summary': () => ({
        rowsThisCycle: 184220,
        legacyRows: null,
        retentionMonths: null,
        retentionSet: false,
      }),
      'GET /v1/admin/audit': () => ({ items: [ROW7], page: 0, size: 20, total: 1 }),
      ...over,
    });

  it('SRS-G-2: every row carries user, address, object, change, effective date, reason, order and authorisation', async () => {
    trail();
    wrap(<AuditTrail />, ['A-05:VIEW']);
    const table = await screen.findByRole('table', { name: t('x04w.mandatory') });
    expect(await within(table).findByText('10.24.6.31')).toBeTruthy();
    expect(within(table).getByText('Y → X')).toBeTruthy();
    expect(within(table).getByText('S. Rani (088412)')).toBeTruthy();
    expect(within(table).getByText('APR-11 · P. Menon')).toBeTruthy();
    // SRS l.1248: the order number links to the document of that audit row.
    expect(within(table).getByRole('link', { name: 'EST/RO-DEL/2026/301' }).getAttribute('href')).toContain(
      '/v1/admin/audit/7/order-documents/91',
    );
  });

  it('SRS-G-13, OD-135, WF-1900: without EXPORT the three drawn exports only say why; nothing is downloaded', async () => {
    const calls = trail();
    const user = userEvent.setup();
    wrap(<AuditTrail />, ['A-05:VIEW']);
    await screen.findByRole('table', { name: t('x04w.trail') });
    for (const name of [t('x04w.exportTrail'), t('wf.export'), t('x04w.act.export')]) {
      const button = screen.getByRole('button', { name });
      expect(button).toBeEnabled();
      expect(button).toHaveAttribute('data-guard', t('x04w.why.export'));
      await user.click(button);
    }
    expect(screen.queryByRole('link', { name: t('x04w.act.export') })).toBeNull();
    expect(calls.some((c) => c.url.includes('/export'))).toBe(false);
  });

  it('SRS-20-3: the enquiry sits in a closed section, Point-in-Time View opens it, and the drawn card prints the choice', async () => {
    trail();
    const user = userEvent.setup();
    wrap(<AuditTrail />, ['A-05:VIEW']);
    await screen.findByRole('table', { name: t('x04w.trail') });
    const section = screen.getByTestId('x04-enquiry') as HTMLDetailsElement;
    expect(section.open).toBe(false);
    expect(screen.getByText(t('x04.legacy.logged')).closest('details')).toBe(section);
    expect(screen.getByText(t('x04.keyHint')).closest('details')).toBe(section);
    expect(screen.getByText(t('x04w.pit.noRecord'))).toBeTruthy();
    await user.click(screen.getByRole('button', { name: t('x04w.pointInTime') }));
    expect(section.open).toBe(true);
    await waitFor(() => expect(document.activeElement).toBe(within(section).getByLabelText(t('x04.type'))));
  });

  it('SRS-20-8: the tiles read the scope of the reader; a legacy count is Headquarters only, retention stays a setting', async () => {
    trail();
    wrap(<AuditTrail />, ['A-05:VIEW']);
    expect(await screen.findByText('1,84,220')).toBeTruthy();
    expect(screen.getByText(t('x04w.tile.legacyBig'))).toBeTruthy();
    expect(screen.getByText(t('x04w.tile.retentionBig'))).toBeTruthy();
  });

  it('SRS-20-5: a confirmed period and a Headquarters count replace the drawn words', async () => {
    trail({
      'GET /v1/admin/audit/summary': () => ({
        rowsThisCycle: 1,
        legacyRows: 2500,
        retentionMonths: 96,
        retentionSet: true,
      }),
    });
    wrap(<AuditTrail />, ['A-05:VIEW']);
    expect(await screen.findByText('2,500')).toBeTruthy();
    expect(screen.getAllByText(t('x04w.months', [96])).length).toBe(2);
  });

  it('SRS l.1226: the drawn filters go to the server, say what is on and clear; the list search waits for typing to stop', async () => {
    const calls = trail();
    wrap(<AuditTrail />, ['A-05:VIEW']);
    await screen.findByRole('table', { name: t('x04w.trail') });
    await userEvent.selectOptions(
      await screen.findByRole('combobox', { name: t('x04w.allObjects') }),
      await screen.findByRole('option', { name: 'Work-slips' }),
    );
    await userEvent.selectOptions(screen.getByRole('combobox', { name: t('x04w.allUsers') }), 'SYSTEM');
    expect(await screen.findByText(t('x04w.filtered'))).toBeTruthy();
    await waitFor(() => {
      const last = calls.filter((c) => c.url.includes('/v1/admin/audit?')).at(-1)?.url ?? '';
      expect(last).toContain('objectGroup=WORK_SLIPS');
      expect(last).toContain('userGroup=SYSTEM');
    });
    await userEvent.click(screen.getByRole('button', { name: t('x04w.clearFilters') }));
    expect(screen.queryByText(t('x04w.filtered'))).toBeNull();
    await userEvent.type(screen.getByRole('searchbox', { name: t('wf.searchInList') }), 'slip');
    await waitFor(() => expect(calls.some((c) => c.url.includes('q=slip'))).toBe(true));
  });

  it('SRS-20-19: a row opens read only with every field; the matrix, coverage and session log are drawn', async () => {
    trail();
    wrap(
      <AuditTrail />,
      me(['A-05:VIEW'], { roleName: 'Payroll Maker', location: { code: 'DH001', label: 'Delhi' } }),
    );
    await userEvent.click(await screen.findByRole('button', { name: t('x04w.viewRow', [7]) }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('CITY_TYPE_HRA')).toBeTruthy();
    expect(within(dialog).getByText(t('x04.outcome.POSTED'))).toBeTruthy();
    expect(within(dialog).queryByRole('button', { name: t('wf.edit') })).toBeNull();
    await userEvent.click(within(dialog).getByRole('button', { name: t('common.close') }));
    expect(within(screen.getByRole('table', { name: t('x04w.matrix') })).getAllByRole('row')).toHaveLength(
      13,
    );
    expect(screen.getByText(t('x04w.coverage.retention.pill'))).toBeTruthy();
    const log = screen.getByRole('table', { name: t('x04w.session') });
    expect(await within(log).findByText('Payroll Maker')).toBeTruthy();
    expect(within(log).getByText('Delhi')).toBeTruthy();
  });

  it('SRS-20-23: with no record chosen nothing legacy is read and the table says how to fill it', async () => {
    const calls = trail();
    wrap(<AuditTrail />, ['A-05:VIEW', 'A-05:EXPORT']);
    expect(await screen.findByText(t('x04.legacy.pick'))).toBeTruthy();
    expect(calls.some((c) => c.url.includes('/v1/audit/legacy'))).toBe(false);
    const legacy = screen.getByText(t('x04.legacy.title')).closest('.card') as HTMLElement;
    const button = within(legacy).getByRole('button', { name: t('x04w.act.export') });
    expect(button).toBeEnabled();
    expect(button).toHaveAttribute('data-guard', t('x04w.why.legacyNoRecord'));
  });
});

describe('SRS-20-29 History button of a master screen', () => {
  it('opens the change history of the record on screen, with the Change History Report and the Point-in-Time Extract', async () => {
    const calls = backend({
      'GET /v1/audit/history/as-of': () => ({
        entity: 'employee',
        key: '104882',
        date: '2026-01-01',
        status: 'FOUND',
        versioned: false,
        versionNo: null,
        effectiveFrom: null,
        effectiveTo: null,
        approvalId: null,
        values: { mobileNumber: '9000020000', bankAccount: '******4412' },
      }),
      'GET /v1/audit/history?': () =>
        view({ entity: 'employee', key: '104882', screen: 'M-01', versioned: false, versions: [] }),
    });
    const user = userEvent.setup();
    wrap(<HistoryButton entity="employee" recordKey="104882" label="104882 · Rakesh Sharma" />, [
      'M-01:VIEW',
      'M-01:EXPORT',
    ]);
    await user.click(screen.getByRole('button', { name: t('history.for', ['104882 · Rakesh Sharma']) }));
    const dialog = await screen.findByRole('dialog', {
      name: t('history.title', ['104882 · Rakesh Sharma']),
    });
    expect((await within(dialog).findAllByText('Sys Admin (100001)')).length).toBe(2);
    // A record kept as one row has no version table.
    expect(within(dialog).queryByText(t('x04.versions'))).toBeNull();
    // EXPORT on the record's own screen: the report is offered; the extract once a date is chosen.
    expect(within(dialog).getAllByRole('button', { name: t('action.export') })).toHaveLength(1);
    await user.type(within(dialog).getByLabelText(t('x04.asOf')), '2026-01-01');
    expect(await within(dialog).findByText(t('x04.asOf.rebuilt', [date('2026-01-01')]))).toBeTruthy();
    expect(within(dialog).getByText('******4412')).toBeTruthy();
    expect(within(dialog).getAllByRole('button', { name: t('action.export') })).toHaveLength(2);
    expect(calls.some((c) => c.url.includes('/v1/audit/history?entity=employee&key=104882'))).toBe(true);
    // Escape and Close both close it.
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).toBeNull();
    await user.click(screen.getByRole('button', { name: t('history.for', ['104882 · Rakesh Sharma']) }));
    await user.click(await screen.findByRole('button', { name: t('history.close') }));
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('without EXPORT nothing can be downloaded; a refusal shows the server reason', async () => {
    backend({ 'GET /v1/audit/history?': () => problem(403, 'AUTHZ-403-SCOPE') });
    const user = userEvent.setup();
    wrap(<HistoryButton entity="employee" recordKey="999999" />, ['M-01:VIEW']);
    await user.click(screen.getByRole('button', { name: t('history.for', ['999999']) }));
    expect((await screen.findAllByText(t('error.AUTHZ-403-SCOPE'))).length).toBeGreaterThan(0);
    expect(screen.queryByRole('button', { name: t('action.export') })).toBeNull();
  });
});

describe('X-04 change history without every particular', () => {
  async function openHistory(user: ReturnType<typeof userEvent.setup>, asOf?: string) {
    await waitFor(() =>
      expect(within(screen.getByLabelText(t('x04.type'))).getAllByRole('option')).toHaveLength(2),
    );
    await user.selectOptions(screen.getByLabelText(t('x04.type')), 'rule_version');
    await user.type(screen.getByLabelText(t('x04.key')), 'OTP_VALIDITY');
    if (asOf) await user.type(screen.getByLabelText(t('x04.asOf')), asOf);
    await user.click(screen.getByRole('button', { name: t('x04.show') }));
  }

  it('SC-03-0012: a change without values, approval, address or author shows dashes, never null', async () => {
    const bare = {
      ...ROW,
      transactionId: 42,
      oldValue: null,
      newValue: null,
      approvalId: null,
      approvalStatus: null,
      sourceIp: null,
    };
    backend({
      'GET /v1/audit/record-types': () => ['rule_version'],
      'GET /v1/audit/history?': () => view({ items: [bare] }),
      'GET /v1/audit/legacy?': () => ({
        ...LEGACY,
        items: [{ ...LEGACY.items[1], id: 3, oldValue: null, newValue: null, changedBy: null, reason: null }],
        total: 1,
      }),
      'GET /v1/admin/audit': () => ({ items: [], page: 0, size: 50, total: 0 }),
    });
    const user = userEvent.setup();
    wrap(<AuditTrail />, ['A-05:VIEW']);
    await openHistory(user);
    const row = (await screen.findByText('42')).closest('tr') as HTMLElement;
    expect(within(row).getAllByText('—').length).toBeGreaterThanOrEqual(4);
    expect(row.textContent).not.toContain('null');
    const legacy = screen.getByText(t('x04.legacy.title')).closest('.card') as HTMLElement;
    expect(within(legacy).getByText('— →')).toBeTruthy();
    expect(within(legacy).getAllByText('—').length).toBeGreaterThanOrEqual(2);
  });

  it('SRS-20-6: a version without a number and a failed as-of lookup are both reported', async () => {
    let fail = false;
    backend({
      'GET /v1/audit/record-types': () => ['rule_version'],
      'GET /v1/audit/history/as-of': () =>
        fail
          ? problem(404, 'AUD-404-RECORD')
          : {
              entity: 'rule_version',
              key: 'OTP_VALIDITY',
              date: '2026-05-01',
              status: 'FOUND',
              versioned: true,
              versionNo: null,
              effectiveFrom: '2026-04-01',
              effectiveTo: null,
              approvalId: null,
              values: { scalar_value: '5' },
            },
      'GET /v1/audit/history?': () => view(),
      'GET /v1/audit/legacy?': () => ({ ...LEGACY, items: [], total: 0 }),
      'GET /v1/admin/audit': () => ({ items: [], page: 0, size: 50, total: 0 }),
    });
    const user = userEvent.setup();
    wrap(<AuditTrail />, ['A-05:VIEW']);
    await openHistory(user, '2026-05-01');
    expect(await screen.findByText(t('x04.asOf.found', [date('2026-05-01'), '']).trim())).toBeTruthy();
    fail = true;
    await user.clear(screen.getByLabelText(t('x04.asOf')));
    await user.type(screen.getByLabelText(t('x04.asOf')), '2026-06-01');
    await user.click(screen.getByRole('button', { name: t('x04.show') }));
    expect(await screen.findByText(t('error.AUD-404-RECORD'))).toBeTruthy();
  });
});
