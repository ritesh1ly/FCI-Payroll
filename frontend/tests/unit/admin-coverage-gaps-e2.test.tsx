import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { SignInSecurity } from '@/modules/iam';
import { FinalSettlement } from '@/modules/settlement';
import { WorkflowMaster } from '@/modules/workflow';
import type { RouteAction, RouteVersion } from '@/modules/workflow/api/workflowMasterApi';
import { GovernedProvider } from '@/modules/workflow';
import { I18nProvider } from '@/shared/i18n';
import { ToastProvider } from '@/shared/ui';
import { backend, me, paged, problem, referenceLists, sent, t, wrap } from './support/harness';

/**
 * Coverage of the remaining paths of X-08, O-05 and A-08 (SRS-G-21, SRS-10-11, SRS-G-30, SRS-22-12): the sign-in card
 * before the session is known, the pending clearances with none awaited, an order type the document master does not
 * hold, and a route with neither a version in force nor a proposal.
 */
afterEach(() => vi.unstubAllGlobals());
Element.prototype.scrollIntoView = vi.fn();

describe('X-08 before the session is known', () => {
  it('SRS_G_21_withoutAProfileTheUserIdIsEmptyAndContinueSaysWhoIsSignedIn', async () => {
    backend({
      'GET /auth/me': () => problem(401, 'AUTH-401-UNAUTHENTICATED'),
      'GET /v1/otp/gated-actions': () => [],
    });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <I18nProvider>
        <QueryClientProvider client={client}>
          <ToastProvider>
            <GovernedProvider>
              <SignInSecurity />
            </GovernedProvider>
          </ToastProvider>
        </QueryClientProvider>
      </I18nProvider>,
    );
    const user = (await screen.findByLabelText(t('x08.userId'))) as HTMLInputElement;
    expect(user.value).toBe('');
    expect(screen.getByRole('button', { name: t('x08.continue') })).toHaveAttribute(
      'data-guard',
      t('x08.why.signedIn', ['']),
    );
  });
});

const SUMMARY = {
  separations: 0,
  settled: 0,
  settledAmount: 0,
  byType: [],
  awaitingClearance: 0,
  hqApproval: 0,
  netRecovery: 0,
};
const ROW = {
  caseId: 7,
  caseReference: 'FNF-2026-00007',
  eis: '104882',
  name: 'Rakesh Sharma',
  office: 'DH001 - Delhi Division',
  separationType: 'RETIREMENT',
  separationDate: '2026-09-30',
  stage: 'CLEARANCES_RAISED',
  awaiting: [],
  status: 'INITIATED',
  netPayable: null,
  payrollType: 'STAFF',
  payrollDues: null,
  totalRecoveries: null,
  recoveredFromGratuity: null,
  awaitingChecker: false,
  hqApprovalPending: false,
};
const DETAIL = {
  summary: ROW,
  settlementId: 3,
  clearances: [],
  pendingSalary: null,
  leaveEncashment: null,
  arrears: null,
  exGratia: null,
  gratuity: null,
  totalRecoveries: null,
  recoveredFromGratuity: null,
  netRecovery: false,
  recoveries: [],
  exGratiaEntries: [],
  cpfStatus: 'NOT_APPLIED',
  cpfAmount: null,
  benefitsHeld: true,
  invoiceStatus: 'NOT_RAISED',
};

describe('O-05 remaining paths', () => {
  function routes(extra: Record<string, (url: string) => unknown> = {}) {
    return backend({
      'GET /v1/reference/lists': () => referenceLists({ ORDER_COPY_DOCUMENT_TYPE: [['OTHER', 'Other']] }),
      'GET /v1/settlement-cases/awaiting': () => [],
      'GET /v1/settlement-cases/summary': () => SUMMARY,
      'GET /v1/settlement-cases/invoices': (url) => paged([], 0, url),
      'GET /v1/settlement-cases?': (url) => paged([ROW], 1, url),
      'GET /v1/settlement-cases/7/orders': () => [],
      'GET /v1/settlement-cases/7': () => DETAIL,
      'GET /v1/document-types': () => [{ code: 'CIRCULAR', name: 'Circular', referenceRequired: true }],
      ...extra,
    });
  }

  it('SRS_10_11_withNoClearanceAwaitedThePendingClearancesSaySo', async () => {
    routes();
    wrap(<FinalSettlement />, me(['O-05:VIEW']));
    await screen.findByText('FNF-2026-00007');
    await userEvent.click(screen.getByRole('button', { name: t('o05.w.pendingClearances') }));
    const dialog = await screen.findByRole('dialog', { name: t('o05.w.pendingClearances') });
    expect(await within(dialog).findByText(t('o05.noneAwaiting'))).toBeInTheDocument();
  });

  it('SRS_G_30_anOrderOfATypeTheMasterDoesNotHoldIsStoredWithoutReference', async () => {
    const calls = routes({ 'POST /v1/settlement-cases/7/orders': () => ({ status: 201, body: { id: 4 } }) });
    wrap(<FinalSettlement />, me(['O-05:VIEW', 'O-05:COMPUTE']));
    await screen.findAllByText('FNF-2026-00007');
    // The card is drawn again for the chosen case once it is read (keyed by the case): take it after that.
    await waitFor(() =>
      expect(calls.some((c) => c.url.endsWith('/v1/settlement-cases/7/orders'))).toBe(true),
    );
    const cardOf = () => document.querySelector('[data-order-card]') as HTMLElement;
    await waitFor(() =>
      expect(within(cardOf()).getByRole('status')).not.toHaveTextContent(t('wf.ord.viewOnly')),
    );
    const card = cardOf();
    await within(card).findByRole('option', { name: 'Other' });
    fireEvent.change(card.querySelector('input[type="date"]') as HTMLInputElement, {
      target: { value: '2026-10-01' },
    });
    const files = card.querySelector('input[type="file"]') as HTMLInputElement;
    await userEvent.upload(files, new File(['%PDF'], 'letter.pdf', { type: 'application/pdf' }));
    await waitFor(() => expect(sent(calls, 'POST', '/v1/settlement-cases/7/orders')).toHaveLength(1));
    const [body] = sent(calls, 'POST', '/v1/settlement-cases/7/orders') as FormData[];
    expect(body?.get('documentType')).toBe('OTHER');
  });
});

const version = (over: Partial<RouteVersion> = {}): RouteVersion => ({
  id: 7,
  versionNo: 1,
  status: 'SUPERSEDED',
  effectiveFrom: '2025-04-01',
  effectiveTo: '2026-03-31',
  actionName: 'Retired action',
  moduleCode: 'GENERAL',
  screenCode: null,
  levelCount: 2,
  otpAction: null,
  attachmentRequired: false,
  bulkApprovalAllowed: false,
  targetTatHours: null,
  approvalId: null,
  levels: [],
  ...over,
});

describe('A-08 remaining paths', () => {
  it('SRS_22_12_aRouteWithOnlyHistoryShowsItsLastVersionAndIsNotInForce', async () => {
    const actions: RouteAction[] = [
      { actionCode: 'OLD_ONE', officeLevel: 'DO', current: null, pending: null, history: [version()] },
    ];
    backend({
      'GET /v1/admin/workflow-actions/unstaffed': () => paged([]),
      'GET /v1/admin/workflow-actions': () => paged(actions),
      'GET /v1/admin/access-matrix': () => paged([]),
      'GET /v1/admin/active-users': () => ({ ...paged([]), inactiveAfterDays: null }),
    });
    wrap(<WorkflowMaster />, ['A-08:VIEW']);
    const row = (await screen.findByText('OLD_ONE', { selector: 'b' })).closest('tr') as HTMLElement;
    expect(row).toHaveTextContent('Retired action');
    expect(within(row).getByText(t('a08.notInForce'))).toBeInTheDocument();
    expect(row.querySelectorAll('td')[2]).toHaveTextContent('2');
  });
});
