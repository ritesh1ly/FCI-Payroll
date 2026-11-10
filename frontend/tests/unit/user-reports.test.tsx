import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { SystemParameters } from '@/modules/configuration';
import { UsersAdmin } from '@/modules/iam';
import { backend, exportedUrl, problem, referenceLists, t, wrap, paged } from './support/harness';

/** SRS-20-5: the audit stores' retention classes as seeded (no period decided). */
const RETENTION = [
  {
    code: 'LEGACY_AUDIT',
    name: 'Legacy Audit table',
    months: null,
    indefinite: false,
    confirmed: false,
    basis: 'SRS-20-5',
    archivalAction: 'RETAIN',
    pendingApprovalId: null,
  },
  {
    code: 'NATIVE_AUDIT',
    name: 'Native audit trail',
    months: null,
    indefinite: false,
    confirmed: false,
    basis: 'SRS-20-5',
    archivalAction: 'RETAIN',
    pendingApprovalId: null,
  },
];

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }));

/**
 * Module 02 F-3 Active User Report and F-7 readiness on A-01 (SRS-G-50, SRS-22-11), and the A-07
 * settings they read: not configured until FCI approves a value, never a guessed default.
 */

afterEach(() => vi.unstubAllGlobals());

const REFERENCE = referenceLists({
  USER_STATUS: [['ACTIVE', 'Active']],
  USER_CATEGORY: [['OPERATIONS', 'Operations']],
});

const userRow = (over: object = {}) => ({
  userId: 10,
  loginId: 'maker.delhi@fci-demo.test',
  displayName: 'Amit Saxena',
  status: 'ACTIVE',
  lastLoginAt: '2026-10-01T04:30:00Z',
  daysSinceLogin: 20,
  inactive: false,
  access: [{ groupCode: 'AG-DH-MAKER', roleCode: 'PAYROLL_MAKER', unit: 'DO — Delhi HQ (DH001)' }],
  ...over,
});

function routes(report: (url: string) => unknown, readiness: unknown = { doMinimumStaff: null, units: [] }) {
  return backend({
    'GET /v1/reference/lists': () => REFERENCE,
    'GET /v1/admin/users': () => ({ items: [], page: 0, size: 50, total: 0 }),
    'GET /v1/admin/active-users': report,
    'GET /v1/admin/readiness': () => readiness,
  });
}

async function openTab(key: string) {
  await userEvent.click(await screen.findByRole('tab', { name: t(`a01.tab.${key}`) }));
}

describe('F-3 Active User Report', () => {
  it('SRS-G-50: without a configured threshold nobody is flagged and the filter is off', async () => {
    routes(() => ({
      inactiveAfterDays: null,
      items: [
        userRow({ inactive: null }),
        userRow({
          userId: 11,
          loginId: 'new@fci-demo.test',
          lastLoginAt: null,
          daysSinceLogin: null,
          inactive: null,
          access: [],
        }),
      ],
      page: 0,
      size: 50,
      total: 2,
    }));
    wrap(<UsersAdmin />, ['A-01:VIEW']);
    await openTab('report');
    expect(await screen.findByText(t('a01.report.notConfigured'))).toBeTruthy();
    expect(screen.getByText(t('a01.report.title', [2]))).toBeTruthy();
    expect(
      (screen.getByRole('checkbox', { name: t('a01.report.inactiveOnly') }) as HTMLInputElement).disabled,
    ).toBe(true);
    expect(screen.queryByText(t('a01.report.inactive'))).toBeNull();
    expect(screen.getByText(t('a01.report.never'))).toBeTruthy();
    expect(screen.getByText(t('a01.report.noAccess'))).toBeTruthy();
    expect(screen.getByText((text) => text.includes(t('role.PAYROLL_MAKER')))).toBeTruthy();
    // No export without A-01 EXPORT.
    expect(screen.queryByRole('button', { name: t('action.export') })).toBeNull();
  });

  it('SRS-G-50: a configured threshold flags inactive users; the filter and the export follow the search', async () => {
    const calls = routes((url) => ({
      inactiveAfterDays: 30,
      items: url.includes('inactiveOnly=true')
        ? [userRow({ userId: 12, loginId: 'idle@fci-demo.test', daysSinceLogin: 45, inactive: true })]
        : [
            userRow(),
            userRow({ userId: 12, loginId: 'idle@fci-demo.test', daysSinceLogin: 45, inactive: true }),
            userRow({
              userId: 13,
              loginId: 'hq@fci-demo.test',
              access: [
                { groupCode: 'AG-HQ', roleCode: 'SYSTEM_ADMINISTRATOR', unit: 'ALL' },
                { groupCode: 'AG-X', roleCode: 'AUDITOR', unit: null },
              ],
            }),
          ],
      page: 0,
      size: 50,
      total: 3,
    }));
    wrap(<UsersAdmin />, ['A-01:VIEW', 'A-01:EXPORT']);
    await openTab('report');
    expect(await screen.findByText(t('a01.report.threshold', [30]))).toBeTruthy();
    expect(screen.getAllByText(t('a01.report.inactive'))).toHaveLength(1);
    expect(screen.getByText(new RegExp(t('a01.report.allOffices')))).toBeTruthy();
    expect(screen.getByText(t('a01.report.days', [45]))).toBeTruthy();
    await userEvent.type(screen.getByLabelText(t('a01.search')), 'idle');
    await userEvent.click(screen.getByRole('checkbox', { name: t('a01.report.inactiveOnly') }));
    await waitFor(() =>
      expect(calls.some((c) => c.url.includes('inactiveOnly=true') && c.url.includes('q=idle'))).toBe(true),
    );
    expect(await exportedUrl('XLSX')).toBe(
      '/nginx/fci-payroll/v1/admin/active-users/export?inactiveOnly=true&q=idle&format=XLSX',
    );
  });

  it('a refused report is shown', async () => {
    routes(() => problem(403, 'AUTHZ-403-SCOPE'));
    wrap(<UsersAdmin />, ['A-01:VIEW']);
    await openTab('report');
    expect(await screen.findByText(t('error.AUTHZ-403-SCOPE'))).toBeTruthy();
  });
});

describe('F-7 Maker / Checker readiness', () => {
  const unit = (over: object = {}) => ({
    locationId: 1,
    code: 'RO-DL',
    label: 'RO — Delhi Region (RO-DL)',
    type: 'RO',
    readiness: 'READY',
    makers: ['maker.delhi@fci-demo.test'],
    checkers: ['checker.delhi@fci-demo.test'],
    ownStaff: 2,
    meetsMinimum: null,
    ...over,
  });

  it('SRS-G-50: each unit is ready, covered from above or not ready; no minimum while unconfigured', async () => {
    routes(() => ({}), {
      doMinimumStaff: null,
      units: [
        unit(),
        unit({
          locationId: 2,
          code: 'NA11',
          label: 'ZO — North Zone',
          readiness: 'COVERED_FROM_ABOVE',
          checkers: [],
        }),
        unit({
          locationId: 3,
          code: 'X',
          label: 'DO — Nowhere',
          type: 'DO',
          readiness: 'NOT_READY',
          makers: [],
          checkers: [],
          ownStaff: 0,
        }),
      ],
    });
    wrap(<UsersAdmin />, ['A-01:VIEW']);
    await openTab('readiness');
    expect(await screen.findByText(t('a01.readiness.minimumNotConfigured'))).toBeTruthy();
    expect(screen.getByText(t('a01.readiness.title', [3]))).toBeTruthy();
    expect(
      within(screen.getByText('RO — Delhi Region (RO-DL)').closest('tr') as HTMLElement).getByText(
        t('a01.readiness.READY'),
      ),
    ).toBeTruthy();
    expect(screen.getByText(t('a01.readiness.COVERED_FROM_ABOVE'))).toBeTruthy();
    expect(screen.getByText(t('a01.readiness.NOT_READY'))).toBeTruthy();
    expect(screen.getAllByText('—').length).toBeGreaterThanOrEqual(3);
  });

  it('SRS-22-11: a configured minimum marks a Divisional Office below it', async () => {
    routes(() => ({}), {
      doMinimumStaff: 3,
      units: [
        unit({ type: 'DO', meetsMinimum: false }),
        unit({ locationId: 2, type: 'DO', meetsMinimum: true }),
      ],
    });
    wrap(<UsersAdmin />, ['A-01:VIEW']);
    await openTab('readiness');
    expect(await screen.findByText(t('a01.readiness.minimum', [3]))).toBeTruthy();
    expect(screen.getAllByText(t('a01.readiness.belowMinimum'))).toHaveLength(1);
  });

  it('no unit in scope says so; a refusal is shown; the users tab comes back', async () => {
    routes(() => ({}), { doMinimumStaff: null, units: [] });
    wrap(<UsersAdmin />, ['A-01:VIEW']);
    await openTab('readiness');
    expect(await screen.findByText(t('a01.readiness.none'))).toBeTruthy();
    await openTab('users');
    expect(await screen.findByText(t('a01.none'))).toBeTruthy();
  });

  it('a refused readiness check is shown', async () => {
    backend({
      'GET /v1/reference/lists': () => REFERENCE,
      'GET /v1/admin/users': () => ({ items: [], page: 0, size: 50, total: 0 }),
      'GET /v1/admin/readiness': () => problem(403, 'AUTHZ-403-SCOPE'),
    });
    wrap(<UsersAdmin />, ['A-01:VIEW']);
    await openTab('readiness');
    expect(await screen.findByText(t('error.AUTHZ-403-SCOPE'))).toBeTruthy();
  });
});

describe('A-07 settings without a value', () => {
  it('a setting the sources give no value for is not configured; its first value is proposed', async () => {
    const calls = backend({
      'GET /v1/admin/retention-classes': () => paged(RETENTION),
      'GET /v1/admin/parameters': () =>
        paged([
          {
            code: 'INACTIVE_USER_DAYS',
            group: 'SECURITY',
            valueType: 'INTEGER',
            value: null,
            minValue: 1,
            maxValue: 3650,
            unit: 'days',
            enforced: true,
            version: null,
            effectiveFrom: null,
            changedBy: null,
            pending: null,
          },
        ]),
      'PUT /v1/admin/parameters/': () => ({ status: 202, body: {} }),
    });
    wrap(<SystemParameters />, ['A-07:VIEW', 'A-07:EDIT']);
    expect(await screen.findByText(t('a07.notConfigured'))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('action.editParameter') }));
    expect((screen.getByLabelText(t('a07.newValue')) as HTMLInputElement).value).toBe('');
    await userEvent.type(screen.getByLabelText(t('a07.newValue')), '90');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'FCI IT policy');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    await waitFor(() =>
      expect(calls.find((c) => c.method === 'PUT')?.body).toEqual({ value: 90, reason: 'FCI IT policy' }),
    );
  });
});
