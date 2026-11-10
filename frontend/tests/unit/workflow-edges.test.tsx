import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { WorkflowMaster } from '@/modules/workflow';
import type { RouteAction, RouteVersion } from '@/modules/workflow/api/workflowMasterApi';
import { date } from '@/shared/ui';
import { backend, paged, sent, t, wrap } from './support/harness';

/**
 * A-08 without every particular (SRS-22-12, SRS-22-19, SRS-G-28): a level without a name is named
 * by its number and marks its OTP, a closed version shows its last day, a blank screen code and a
 * cleared OTP action go as none, an answer without an approval id is still announced, and an item
 * nobody can decide is listed without its module or office.
 */

afterEach(() => vi.unstubAllGlobals());
Element.prototype.scrollIntoView = vi.fn();

const version = (over: Partial<RouteVersion> = {}): RouteVersion => ({
  id: 7,
  versionNo: 1,
  status: 'ACTIVE',
  effectiveFrom: '2026-04-01',
  effectiveTo: null,
  actionName: 'Off-cycle payment',
  moduleCode: 'GENERAL',
  screenCode: 'I-20',
  levelCount: 2,
  otpAction: null,
  attachmentRequired: true,
  bulkApprovalAllowed: false,
  targetTatHours: 72,
  approvalId: null,
  levels: [
    { levelNo: 1, roleCode: 'PAYROLL_MAKER', officeLevel: 'PAYING_UNIT', otpAtLevel: true },
    {
      levelNo: 2,
      name: 'CHECKER',
      roleCode: 'PAYROLL_CHECKER',
      officeLevel: 'PAYING_UNIT',
      otpAtLevel: false,
    },
  ],
  ...over,
});

const ACTIONS: RouteAction[] = [
  {
    actionCode: 'I20_SUBMIT',
    officeLevel: 'PAYING_UNIT',
    current: version({ effectiveTo: '2026-12-31' }),
    pending: null,
    history: [],
  },
];

const UNSTAFFED = [
  {
    approvalId: 99,
    moduleCode: null,
    entityName: 'employee_lifecycle_event',
    entityKey: '12',
    submittedBy: 'hr.delhi@fci-demo.test',
    submittedAt: '2026-10-20T04:30:00Z',
    waitingForRole: 'LABOUR_DEALING_HAND',
    office: null,
    level: 2,
    levelCount: 2,
  },
];

const normalised = (text: string) => text.replace(/\s+/g, ' ').trim();

describe('A-08 without every particular', () => {
  it('SRS-22-12: an unnamed level is numbered and marks its OTP; a closed version shows its end; blank codes go as none; an item for nobody lists bare', async () => {
    const calls = backend({
      'GET /v1/admin/workflow-actions/unstaffed': () => paged(UNSTAFFED),
      'GET /v1/admin/workflow-actions': () => paged(ACTIONS),
      'POST /v1/admin/workflow-actions': () => ({ status: 202, body: {} }),
      'GET /v1/admin/access-matrix': () => paged([]),
      'GET /v1/admin/active-users': () => ({ ...paged([]), inactiveAfterDays: null }),
    });
    wrap(<WorkflowMaster />, ['A-08:VIEW', 'A-08:CREATE', 'A-08:EDIT']);
    const row = (await screen.findByText('I20_SUBMIT', { selector: 'b' })).closest('tr') as HTMLElement;
    await userEvent.click(within(row).getByRole('button', { name: `${t('common.view')} I20_SUBMIT` }));
    expect(screen.getByText(/· OTP/).textContent).toContain(t('a08.level.MAKER'));
    expect(screen.getAllByText(new RegExp(date('2026-12-31'))).length).toBeGreaterThan(0);

    await userEvent.click(within(row).getByRole('button', { name: `${t('common.edit')} I20_SUBMIT` }));
    const screenCode = screen.getByLabelText(t('a08.screen')) as HTMLInputElement;
    await userEvent.clear(screenCode);
    await userEvent.type(screenCode, '   ');
    const otp = screen.getByLabelText(t('a08.otp')) as HTMLSelectElement;
    const anyOtp = within(otp)
      .getAllByRole('option')
      .find((o) => (o as HTMLOptionElement).value !== '');
    if (anyOtp) {
      await userEvent.selectOptions(otp, (anyOtp as HTMLOptionElement).value);
    }
    await userEvent.selectOptions(otp, '');
    expect(otp.value).toBe('');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Simplify the route');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    const announced = normalised(t('a08.proposed', ['']));
    expect(await screen.findByText((content) => normalised(content) === announced)).toBeTruthy();
    expect(sent(calls, 'POST', '/v1/admin/workflow-actions').at(-1)).toMatchObject({
      screenCode: null,
      otpAction: null,
    });

    await userEvent.click(screen.getByRole('link', { name: t('a08.tab.unstaffed', [1]) }));
    const item = (await screen.findByText('hr.delhi@fci-demo.test')).closest('tr') as HTMLElement;
    expect(within(item).getByText('—')).toBeTruthy();
    expect(item.textContent).not.toContain('null');
  });
});
