import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { HrmsFallback } from '@/modules/general';
import type { Enablement, FallbackUpload } from '@/modules/general/api/generalApi';
import { date } from '@/shared/ui';
import { backend, paged, referenceLists, t, wrap } from './support/harness';

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }));

/**
 * X-07 HRMS Fallback Upload (SRS-G-21, X-07 fallback (a) / (b), Annexure A.11): a reconciled enablement shows when HRMS
 * came back; the upload decided by a Checker names them; a reconciliation row without the member's name shows the
 * number only; the decision form is never submitted by Enter.
 */

afterEach(() => vi.unstubAllGlobals());

const ENABLEMENT: Enablement = {
  id: 4,
  reference: 'FBE-2026-001',
  type: 'SUSPENSION_ABSCONDING',
  payrollYymm: '202610',
  offices: ['DH001'],
  reason: 'HRMS down',
  enabledBy: 'sysadmin',
  enabledAt: '2026-10-01T05:00:00Z',
  status: 'OPEN',
  withdrawnReason: null,
  officeNames: ['DH001 - Delhi HQ'],
  hrmsRestoredAt: null,
};

const UPLOAD: FallbackUpload = {
  id: 9,
  batchNo: 'FBU-1',
  enablementId: 4,
  type: 'SUSPENSION_ABSCONDING',
  office: 'DH001',
  payrollYymm: '202610',
  fileName: 'export.csv',
  recordCount: 3,
  acceptedCount: 2,
  rejectedCount: 1,
  uploadedBy: 'maker.delhi',
  uploadedAt: '2026-10-02T05:00:00Z',
  status: 'VALIDATED',
  checkerUser: null,
  decisionReason: null,
  reconStatus: 'NOT_DUE',
  orderCopies: 0,
  sourceAttachmentId: 31,
  approvalId: null,
  enablementReference: 'FBE-2026-001',
};

function routes(enablements: Enablement[], uploads: FallbackUpload[], recon: object[] = []) {
  return backend({
    'GET /v1/hrms-fallback/enablements': (url) =>
      url.includes('/live')
        ? enablements.filter((e) => e.status === 'OPEN' || e.status === 'UPLOADED')
        : paged(enablements, enablements.length, url),
    'GET /v1/hrms-fallback/uploads': (url) =>
      url.includes('/rejections')
        ? []
        : url.includes('/lines')
          ? paged([], 0, url)
          : paged(uploads, uploads.length, url),
    'GET /v1/hrms-fallback/summary': () => ({ awaitingApproval: 1, reconciliationPending: 0 }),
    'GET /v1/hrms-fallback/interface-status': () => ({ down: false, since: null }),
    'GET /v1/hrms-fallback/reconciliation': (url) => paged(recon, recon.length, url),
    'GET /v1/document-types': () => [],
    'GET /v1/reference/lists': () =>
      referenceLists({
        HRMS_FALLBACK_TYPE: [['SUSPENSION_ABSCONDING', '(b) Suspension, absconding, revocation']],
        ORDER_COPY_DOCUMENT_TYPE: [['OTHER', 'Other']],
      }),
  });
}

describe('X-07 as recorded', () => {
  it('Annexure A.11: a reconciled enablement shows the day HRMS came back; the decided upload names its Checker', async () => {
    routes(
      [{ ...ENABLEMENT, status: 'RECONCILED', hrmsRestoredAt: '2026-10-05T05:00:00Z' }],
      [{ ...UPLOAD, status: 'APPROVED', checkerUser: 'checker.delhi' }],
    );
    wrap(<HrmsFallback />, ['X-07:VIEW']);
    expect(
      await screen.findByText(t('x07.w.enablement.RECONCILED', [date('2026-10-05T05:00:00Z')])),
    ).toBeTruthy();
    expect(await screen.findByText(t('x07.w.checkerValue', ['checker.delhi']))).toBeTruthy();
    expect(screen.queryByText(t('x07.w.checkerPending'))).toBeNull();
  });

  it('Annexure A.11: a difference for a member without a name shows the employee number alone', async () => {
    routes(
      [ENABLEMENT],
      [UPLOAD],
      [
        {
          reference: 'FBE-2026-001',
          payrollYymm: '202610',
          employeeNumber: '103884',
          employeeName: null,
          others: 0,
          item: 'LWP_DAYS',
          uploaded: '2',
          hrms: '3',
          effect: 'RECOVERY',
          difference: '1',
          nextCycleYymm: '202611',
          status: 'DIFFERENCE',
        },
        {
          reference: 'FBE-2026-001',
          payrollYymm: '202610',
          employeeNumber: null,
          employeeName: 'J. Sen',
          others: 0,
          item: 'HALF_PAY_DAYS',
          uploaded: null,
          hrms: '2',
          effect: 'NONE',
          difference: null,
          nextCycleYymm: null,
          status: 'MATCHED',
        },
      ],
    );
    wrap(<HrmsFallback />, ['X-07:VIEW']);
    const cell = await screen.findByText('103884');
    expect(cell.textContent).toBe('103884');
    // A member without a number reads a dash before the name.
    expect(screen.getByText('— J. Sen')).toBeTruthy();
  });

  it('SRS-G-21: the Checker’s decision form is never submitted by Enter; nothing is decided', async () => {
    const calls = routes([ENABLEMENT], [{ ...UPLOAD, status: 'PENDING_APPROVAL', approvalId: 77 }]);
    wrap(<HrmsFallback />, ['X-07:VIEW', 'X-07:APPROVE']);
    await screen.findByText(t('x07.w.fileValue', ['export.csv']));
    await waitFor(() =>
      expect(screen.getAllByRole('button', { name: t('x07.w.act.approve') })[0]).not.toHaveAttribute(
        'data-guard',
      ),
    );
    await userEvent.click(screen.getAllByRole('button', { name: t('x07.w.act.approve') })[0] as HTMLElement);
    const dialog = await screen.findByRole('dialog');
    fireEvent.submit(dialog);
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
    expect(within(dialog).getByRole('button', { name: t('x07.w.act.approve') })).toBeTruthy();
  });

  it('SRS-G-21: an upload opened from the history of another enablement stays open on the card', async () => {
    const other: FallbackUpload = {
      ...UPLOAD,
      id: 10,
      batchNo: 'FBU-2',
      enablementId: 5,
      fileName: 'second.csv',
      enablementReference: 'FBE-2026-002',
    };
    const calls = backend({
      'GET /v1/hrms-fallback/enablements': (url) =>
        url.includes('/live') ? [ENABLEMENT] : paged([ENABLEMENT], 1, url),
      'GET /v1/hrms-fallback/uploads': (url) =>
        url.includes('/rejections')
          ? []
          : url.includes('/lines')
            ? paged([], 0, url)
            : url.includes('enablementId=4')
              ? paged([UPLOAD], 1, url)
              : paged([UPLOAD, other], 2, url),
      'GET /v1/hrms-fallback/summary': () => ({ awaitingApproval: 0, reconciliationPending: 0 }),
      'GET /v1/hrms-fallback/interface-status': () => ({ down: false, since: null }),
      'GET /v1/hrms-fallback/reconciliation': (url) => paged([], 0, url),
      'GET /v1/document-types': () => [],
      'GET /v1/reference/lists': () => referenceLists({ ORDER_COPY_DOCUMENT_TYPE: [['OTHER', 'Other']] }),
    });
    wrap(<HrmsFallback />, ['X-07:VIEW']);
    const enablement = await waitFor(() => {
      const found = document.querySelector<HTMLElement>('tr[data-reference="FBE-2026-001"]');
      if (!found) throw new Error('no enablement');
      return found;
    });
    await userEvent.click(within(enablement).getAllByRole('cell')[0] as HTMLElement);
    await waitFor(() => expect(calls.some((c) => c.url.includes('enablementId=4'))).toBe(true));
    await userEvent.click(await screen.findByRole('button', { name: t('x07.w.history') }));
    const row = await waitFor(() => {
      const found = document.querySelector<HTMLElement>('tr[data-reference="FBU-2"]');
      if (!found) throw new Error('no upload');
      return found;
    });
    await userEvent.click(within(row).getByRole('button', { name: t('x07.lines') }));
    expect(await screen.findByText(t('x07.w.fileValue', ['second.csv']))).toBeTruthy();
  });

  it('SRS-G-21: with no upload at all the history opens with none selected', async () => {
    routes([ENABLEMENT], []);
    wrap(<HrmsFallback />, ['X-07:VIEW']);
    await userEvent.click(await screen.findByRole('button', { name: t('x07.w.history') }));
    await waitFor(() =>
      expect(document.querySelector('tr[aria-selected="true"][data-reference^="FBU"]')).toBeNull(),
    );
    expect(screen.queryByText(t('x07.w.fileValue', ['export.csv']))).toBeNull();
  });

  it('X-07 fallback (b): an order of a type the type list does not hold is attached to the upload without a reference', async () => {
    const calls = routes([ENABLEMENT], [UPLOAD]);
    wrap(<HrmsFallback />, ['X-07:VIEW', 'X-07:SUBMIT']);
    await screen.findByText(t('x07.w.fileValue', ['export.csv']));
    const card = document.querySelector('[data-order-card]') as HTMLElement;
    await within(card).findByRole('option', { name: 'Other' });
    const file = card.querySelector('input[type=file]') as HTMLInputElement;
    await waitFor(() => expect(file.disabled).toBe(false));
    fireEvent.change(card.querySelector('input[type=date]') as HTMLInputElement, {
      target: { value: '2026-10-01' },
    });
    fireEvent.change(within(card).getByPlaceholderText(t('wf.ord.officePlaceholder')), {
      target: { value: 'Vigilance, Delhi' },
    });
    fireEvent.change(file, {
      target: { files: [new File(['%PDF'], 'order.pdf', { type: 'application/pdf' })] },
    });
    await waitFor(() =>
      expect(
        calls.filter((c) => c.method === 'POST' && c.url.endsWith('/v1/hrms-fallback/uploads/9/order')),
      ).toHaveLength(1),
    );
  });
});
