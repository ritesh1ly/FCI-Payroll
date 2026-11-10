import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Item } from '@/modules/workflow/api/workflowApi';
import { MySubmissions } from '@/modules/workflow/components/MySubmissions';
import { Dashboard } from '@/modules/shell/components/Dashboard';
import { wireframeBody } from '@/shared/server/wireframes';
import { backend, me, problem, t, wrap, paged } from './support/harness';

/**
 * The branches the screen tests leave out: W-02 rows with and without each optional field, the
 * workspace home in each step-up state (SRS-G-48), and the server-only wireframe body (deny by
 * default, the session's own role's body only).
 */

afterEach(() => {
  vi.unstubAllGlobals();
  vi.doUnmock('@/generated/wireframes.json');
  vi.resetModules();
});

const item = (over: Partial<Item> = {}): Item => ({
  approvalId: 51,
  moduleCode: 'M-14',
  entityName: 'net_pay_floor_rule',
  entityKey: 'NPF-1',
  actionType: 'CREATE',
  status: 'PENDING_APPROVAL',
  submittedBy: 'maker.delhi@fci-demo.test',
  submittedAt: '2026-10-19T04:30:00Z',
  reason: 'New floor',
  assignedRoleCode: 'PAYROLL_CHECKER',
  locationLabel: 'DO — Delhi HQ (DH001)',
  slaDueAt: null,
  slaBreached: false,
  impactValue: null,
  ageDays: 0,
  targetTatHours: null,
  level: 1,
  levelCount: 1,
  noApproverInScope: false,
  canDecide: false,
  denialKey: null,
  bulkApproval: false,
  ...over,
});

const row = (id: number) => screen.getByText(String(id), { selector: 'b' }).closest('tr') as HTMLElement;

describe('W-02 My Submissions', () => {
  it('SRS-22-4: each row shows its checker, or a dash and the no-approver flag', async () => {
    backend({
      'GET /v1/submissions': () =>
        paged([
          item(),
          item({
            approvalId: 52,
            moduleCode: null,
            assignedRoleCode: null,
            noApproverInScope: true,
            locationLabel: null,
            reason: null,
          }),
        ]),
    });
    wrap(<MySubmissions />, ['W-02:VIEW']);
    expect(await screen.findByText(t('w02.title', [2]))).toBeTruthy();
    const first = within(row(51));
    expect(first.getByText('M-14')).toBeTruthy();
    expect(first.getByText(t('role.PAYROLL_CHECKER'))).toBeTruthy();
    expect(first.getByText('New floor')).toBeTruthy();
    expect(first.queryByText(t('w01.noApprover'))).toBeNull();
    const second = within(row(52));
    // The checker, the reason and the reference, plus the undecided Checker's decision (SRS l.596).
    expect(second.getAllByText('—')).toHaveLength(4);
    expect(second.getByText(t('w01.noApprover'))).toBeTruthy();
    expect(screen.queryByText(t('w02.none'))).toBeNull();
  });

  it('SRS-22-4: an empty list says so; a failed load shows the problem and no empty note', async () => {
    backend({ 'GET /v1/submissions': () => paged([]) });
    const view = wrap(<MySubmissions />, ['W-02:VIEW']);
    expect(await screen.findByText(t('w02.none'))).toBeTruthy();
    view.unmount();

    backend({ 'GET /v1/submissions': () => problem(500, 'COM-500') });
    wrap(<MySubmissions />, ['W-02:VIEW']);
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.queryByText(t('w02.none'))).toBeNull();
    expect(screen.getByText(t('w02.title', [0]))).toBeTruthy();
  });
});

describe('Workspace home', () => {
  const tiles = [
    { id: 'M-14', code: 'M-14', labelKey: 'screen.M-14', route: '/payroll?s=M-14', requires: 'M-14:VIEW' },
    { id: 'Z-99', code: 'Z-99', labelKey: 'screen.Z-99', route: '/payroll?s=Z-99', requires: 'Z-99:VIEW' },
  ];

  it('SRS-G-48: no sensitive screen needs no OTP; tiles open their screen', async () => {
    const onOpen = vi.fn();
    wrap(<Dashboard me={me([])} items={tiles} onOpen={onOpen} onVerify={vi.fn()} />);
    expect(screen.getByText(t('role.PAYROLL_MAKER'))).toBeTruthy();
    expect(screen.getByText('DO — Delhi HQ (DH001)')).toBeTruthy();
    expect(screen.getByText(t('dash.otpNotNeeded'))).toBeTruthy();
    expect(screen.queryByLabelText(t('dash.sensitive'))).toBeNull();
    // A screen the registry does not know still gets a tile (default icon).
    await userEvent.click(screen.getByRole('button', { name: /Z-99/ }));
    expect(onOpen).toHaveBeenCalledWith('Z-99');
  });

  it('SRS-G-48: a sensitive screen without a valid step-up offers Verify and marks the tile', async () => {
    const onVerify = vi.fn();
    const profile = me([], {
      role: null,
      roleName: 'Custom role',
      location: null,
      sensitiveScreens: ['M-14'],
      stepUpValidUntil: null,
    });
    const { container } = wrap(<Dashboard me={profile} items={tiles} onOpen={vi.fn()} onVerify={onVerify} />);
    expect(screen.getByText('Custom role')).toBeTruthy();
    expect(screen.getByText(t('scope.noOffice'))).toBeTruthy();
    expect(screen.getByText(t('dash.otpDue'))).toBeTruthy();
    expect(container.querySelector('.dash-card.warn')).not.toBeNull();
    expect(screen.getByLabelText(t('dash.sensitive'))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: new RegExp(t('dash.otpVerify')) }));
    expect(onVerify).toHaveBeenCalledOnce();
  });

  it('SRS-G-48: a valid step-up shows until when; no role name and no screens are handled', () => {
    const profile = me([], {
      role: null,
      roleName: null,
      sensitiveScreens: ['M-14'],
      stepUpValidUntil: '2999-01-01T00:00:00Z',
    });
    const { container } = wrap(<Dashboard me={profile} items={[]} onOpen={vi.fn()} onVerify={vi.fn()} />);
    expect(screen.queryByText(t('dash.otpDue'))).toBeNull();
    expect(screen.queryByRole('button', { name: new RegExp(t('dash.otpVerify')) })).toBeNull();
    expect(container.querySelector('.dash-card.warn')).toBeNull();
    expect(screen.getByText(t('nav.noScreens'))).toBeTruthy();
  });
});

describe('wireframe body (server only)', () => {
  const session = (roles: string[], permissions = ['M-01:VIEW']) => ({ roles, permissions });

  it('deny by default: no wireframe, an invalid code or no VIEW gives nothing', () => {
    expect(wireframeBody(session(['PAYROLL_MAKER']), 'M-04')).toBeNull();
    expect(wireframeBody(session(['PAYROLL_MAKER'], []), 'M-01')).toBeNull();
    expect(wireframeBody(session(['PAYROLL_MAKER'], ['m-01:VIEW']), 'm-01')).toBeNull();
  });

  it("serves the session's own role's body, the admin body for any other role", () => {
    const maker = wireframeBody(session(['PAYROLL_MAKER']), 'M-01');
    const checker = wireframeBody(session(['PAYROLL_CHECKER', 'PAYROLL_MAKER']), 'M-01');
    const admin = wireframeBody(session(['SYSTEM_ADMINISTRATOR']), 'M-01');
    const finance = wireframeBody(session(['FINANCE']), 'M-01');
    expect(maker).not.toBeNull();
    expect(checker).not.toEqual(maker);
    // M-01 has no Finance body: Finance sees the admin one.
    expect(finance).toEqual(admin);
  });

  it('falls back to the first body, then to none, when the design pack lacks one', async () => {
    vi.doMock('@/generated/wireframes.json', () => ({
      default: { m1: { id: 'm1', code: 'M-01', name: '', crumb: '', bodies: { hr: ['hr-body'] } } },
    }));
    const first = await import('@/shared/server/wireframes');
    expect(first.wireframeBody(session(['PAYROLL_MAKER']), 'M-01')).toEqual(['hr-body']);
    vi.resetModules();

    vi.doMock('@/generated/wireframes.json', () => ({
      default: { m1: { id: 'm1', code: 'M-01', name: '', crumb: '', bodies: {} } },
    }));
    const none = await import('@/shared/server/wireframes');
    expect(none.wireframeBody(session(['PAYROLL_MAKER']), 'M-01')).toEqual([]);
    vi.resetModules();

    vi.doMock('@/generated/wireframes.json', () => ({ default: {} }));
    const missing = await import('@/shared/server/wireframes');
    expect(missing.wireframeBody(session(['PAYROLL_MAKER']), 'M-01')).toBeNull();
  });
});
