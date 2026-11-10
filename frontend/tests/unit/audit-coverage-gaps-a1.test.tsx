import { render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AuditTrail } from '@/modules/audit';
import { AsOfPanel } from '@/modules/audit/components/RecordHistory';
import { I18nProvider } from '@/shared/i18n';
import { date } from '@/shared/ui';
import { backend, me, problem, t, wrap } from './support/harness';

/**
 * A-05 / X-04 Audit Trail & Change History (SRS-20-5, SRS-20-6, SRS-20-19, SRS-20-23, SC-03-0012): a retention period
 * confirmed without its months, a change approved with no Checker named, a row without values, address or module, the
 * session log of a reader with no login, role or office, the record types offered as suggestions, a record rebuilt
 * (not versioned) as of a date, and the as-of panel of the record history in each of its states.
 */

afterEach(() => vi.unstubAllGlobals());

const ROW7 = {
  id: 7,
  changedAt: '2026-10-21T10:00:00Z',
  changedBy: 'maker.delhi@fci-demo.test',
  entityName: 'employee',
  entityKey: '104882',
  fieldName: 'CITY_TYPE_HRA',
  oldValue: null,
  newValue: null,
  reason: 'City re-classification',
  approvalId: 11,
  moduleCode: null,
  sourceIp: null,
  effectiveDate: '2026-01-01',
  outcome: 'POSTED',
  approvalStatus: 'APPROVED',
  maker: { loginId: 'maker.delhi@fci-demo.test', name: 'S. Rani', employeeNumber: '088412' },
  checker: null,
  order: null,
};

function trail(over: Record<string, (url: string) => unknown> = {}) {
  return backend({
    'GET /v1/reference/lists': () => ({ asOf: '2026-11-07', lists: {} }),
    'GET /v1/admin/audit/summary': () => ({
      rowsThisCycle: 1,
      legacyRows: null,
      retentionMonths: null,
      retentionSet: true,
    }),
    'GET /v1/audit/record-types': () => ['rule_version'],
    'GET /v1/admin/audit': () => ({ items: [ROW7], page: 0, size: 20, total: 1 }),
    ...over,
  });
}

describe('X-04 the trail', () => {
  it('SRS-20-5: a retention confirmed without its months reads the months label with no number', async () => {
    trail();
    wrap(<AuditTrail />, ['A-05:VIEW']);
    expect((await screen.findAllByText(t('x04w.months', ['']).trim())).length).toBe(2);
    expect(screen.queryByText(t('x04w.tile.retentionBig'))).toBeNull();
  });

  it('SRS-20-19: an approved change with no Checker named shows its approval alone; missing values read a dash', async () => {
    trail();
    wrap(<AuditTrail />, ['A-05:VIEW']);
    await userEvent.click(await screen.findByRole('button', { name: t('x04w.viewRow', [7]) }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('APR-11')).toBeTruthy();
    // Old, new, address and module are not recorded.
    expect(within(dialog).getAllByText('—').length).toBeGreaterThanOrEqual(4);
  });

  it('SRS-20-19: the session log of a reader with no role or office reads dashes', async () => {
    trail();
    wrap(
      <AuditTrail />,
      me(['A-05:VIEW'], {
        roleName: null as unknown as string,
        location: null as unknown as { code: string; label: string },
      }),
    );
    const log = await screen.findByRole('table', { name: t('x04w.session') });
    await waitFor(() => expect(within(log).getAllByText('—').length).toBeGreaterThanOrEqual(2));
  });

  it('SRS-20-19: a reader with no login has no session log to read', async () => {
    const calls = trail();
    wrap(<AuditTrail />, me(['A-05:VIEW'], { loginId: undefined as unknown as string }));
    const log = await screen.findByRole('table', { name: t('x04w.session') });
    // Until the session names its login the log waits: no own-session page is read.
    expect(within(log).getByText(t('common.loading'))).toBeTruthy();
    await waitFor(() => expect(calls.some((c) => c.url.includes('/v1/admin/audit?'))).toBe(true));
    expect(calls.some((c) => /actor=[^&]/.test(c.url))).toBe(false);
  });

  it('SRS-20-23: the record types are offered as suggestions in the filter', async () => {
    trail();
    const { container } = wrap(<AuditTrail />, ['A-05:VIEW']);
    await userEvent.click(await screen.findByRole('button', { name: t('x04w.filter') }));
    await waitFor(() =>
      expect(container.querySelector('#a05-entity-types option[value="rule_version"]')).not.toBeNull(),
    );
    expect(
      container.querySelector('#a05-entity-types option[value="rule_version"]')?.getAttribute('label'),
    ).toBe(t('x04.entity.rule_version'));
  });
});

describe('X-04 the record as of a date', () => {
  const view = {
    entity: 'rule_version',
    key: 'OTP_VALIDITY',
    screen: 'A-07',
    versioned: false,
    versions: [],
    items: [],
    page: 0,
    size: 50,
    total: 0,
  };

  it('SRS-20-6: a record that keeps no versions is rebuilt as of the date', async () => {
    trail({
      'GET /v1/audit/history/as-of': () => ({
        entity: 'rule_version',
        key: 'OTP_VALIDITY',
        date: '2026-05-01',
        status: 'FOUND',
        versioned: false,
        versionNo: null,
        effectiveFrom: null,
        effectiveTo: null,
        approvalId: null,
        values: { scalar_value: '5' },
      }),
      'GET /v1/audit/history?': () => view,
      'GET /v1/audit/legacy?': () => ({
        entity: 'rule_version',
        key: 'OTP_VALIDITY',
        items: [
          {
            id: 9,
            origin: 'LEGACY',
            sourceObject: 'PAY_RULE_HIST',
            sourceKey: 'RULE=OTP',
            grain: 'FIELD',
            actionCode: 'U',
            fieldName: null,
            oldValue: '4',
            newValue: '5',
            rowImage: null,
            changedFields: null,
            changedBy: 'LEGACYUSR01',
            changedAt: '2016-07-01T04:30:00Z',
            reason: null,
          },
        ],
        page: 0,
        size: 50,
        total: 1,
      }),
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
    expect(await screen.findByText(t('x04.asOf.rebuilt', [date('2026-05-01')]))).toBeTruthy();
    // A legacy row naming neither a field nor the changed fields reads a dash.
    const legacy = screen.getByText(t('x04.legacy.title')).closest('.card') as HTMLElement;
    const row = (await within(legacy).findByText('PAY_RULE_HIST')).closest('tr') as HTMLElement;
    expect(within(row).getAllByText('—').length).toBeGreaterThanOrEqual(1);
  });
});

describe('Record history as-of panel', () => {
  function panel(answer: () => unknown, day = '2026-05-01') {
    backend({ 'GET /v1/audit/history/as-of': answer });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    return render(
      <I18nProvider>
        <QueryClientProvider client={client}>
          <AsOfPanel record={{ entity: 'rule_version', key: 'OTP_VALIDITY' }} day={day} />
        </QueryClientProvider>
      </I18nProvider>,
    );
  }
  const found = (over: object = {}) => ({
    entity: 'rule_version',
    key: 'OTP_VALIDITY',
    date: '2026-05-01',
    status: 'FOUND',
    versioned: true,
    versionNo: 3,
    effectiveFrom: '2026-04-01',
    effectiveTo: null,
    approvalId: null,
    values: { scalar_value: '5' },
    ...over,
  });

  it('SRS-20-6: a versioned record names its version as of the date', async () => {
    panel(() => found());
    expect(await screen.findByText(t('x04.asOf.found', [date('2026-05-01'), 3]))).toBeTruthy();
  });

  it('SRS-20-6: a version without a number and a rebuilt record are both said', async () => {
    const first = panel(() => found({ versionNo: null }));
    expect(await screen.findByText(t('x04.asOf.found', [date('2026-05-01'), '']).trim())).toBeTruthy();
    first.unmount();
    panel(() => found({ versioned: false }));
    expect(await screen.findByText(t('x04.asOf.rebuilt', [date('2026-05-01')]))).toBeTruthy();
  });

  it('SRS-20-6: a record not in force on the date says so; a failed read shows the reason; no day shows nothing', async () => {
    const first = panel(() => found({ status: 'NOT_IN_FORCE' }));
    expect(await screen.findByText(t('x04.asOf.notInForce', [date('2026-05-01')]))).toBeTruthy();
    first.unmount();
    const second = panel(() => problem(404, 'AUD-404-RECORD'));
    expect(await screen.findByText(t('error.AUD-404-RECORD'))).toBeTruthy();
    second.unmount();
    const { container } = panel(() => found(), '');
    expect(container.textContent).toBe('');
  });
});
