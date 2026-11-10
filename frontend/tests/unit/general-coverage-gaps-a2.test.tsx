import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { PayrollQueries } from '@/modules/general';
import { generalApi } from '@/modules/general/api/generalApi';
import { Controls, InvoicePill, Note, StoredOrders } from '@/modules/general/components/Parts';
import { generalPages } from '@/modules/general/orchestration/generalQueries';
import { I18nProvider } from '@/shared/i18n';
import { backend, paged, problem, t, wrap } from './support/harness';

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }));

/**
 * The shared parts and reads of the general module: the invoice pill of a payment sent to Apps (BR-AUDIT-010), a
 * stored-order list with no orders or a failed read (SRS-G-30), the control rows with a right-hand pill and the note
 * box (wireframe "oq"); the X-06 export with and without a status (SRS-G-13), the C-08 items of a condition with no
 * module, and the I-20 / I-23 registers of every type.
 */

afterEach(() => vi.unstubAllGlobals());

describe('general parts', () => {
  it('BR-AUDIT-010: an invoice sent to Apps is informational', () => {
    render(
      <I18nProvider>
        <InvoicePill status="SENT_TO_APPS" />
        <InvoicePill status="NOT_RAISED" />
      </I18nProvider>,
    );
    expect(screen.getByText(t('inv.SENT_TO_APPS'))).toHaveClass('p-info');
    expect(screen.getByText(t('inv.NOT_RAISED'))).toHaveClass('p-warn');
  });

  it('SRS-G-30: no stored orders (a failed read) shows the error and says there are none', () => {
    render(
      <I18nProvider>
        <StoredOrders orders={undefined} error={problem(500, 'SYS-500').body} href={(id) => `/files/${id}`} />
      </I18nProvider>,
    );
    expect(screen.getByText(t('ord.none'))).toBeTruthy();
    expect(screen.queryByRole('link', { name: t('ord.view') })).toBeNull();
  });

  it('SRS-G-30: a stored order without a type name shows its type code and opens from View', () => {
    render(
      <I18nProvider>
        <StoredOrders
          orders={[
            {
              id: 5,
              documentTypeCode: 'OTHER',
              documentTypeName: 'Other document',
              referenceNumber: 'EST/5',
              orderDate: null,
              issuingAuthority: null,
              fileName: 'order.pdf',
              status: 'ACTIVE',
              uploadedBy: 'maker.delhi',
              uploadedAt: '2026-07-12T05:34:00Z',
            },
          ]}
          error={null}
          href={(id) => `/files/${id}`}
        />
      </I18nProvider>,
    );
    expect(screen.queryByText(t('ord.none'))).toBeNull();
    expect(screen.getByText('Other document')).toBeTruthy();
    expect(screen.getByRole('link', { name: t('ord.view') }).getAttribute('href')).toBe('/files/5');
  });

  it('the control rows show a sub-text, a source badge and a right-hand pill; the note box its text', () => {
    const { container } = render(
      <I18nProvider>
        <Controls
          title="Controls"
          refs="SRS-G-9"
          items={[
            {
              icon: 'i',
              tone: 'ok',
              label: 'With pill',
              s: 'RULE',
              sub: 'Sub text',
              right: <span>Right pill</span>,
            },
            { icon: 'j', tone: 'bad', label: 'Bare' },
          ]}
        />
        <Note>Held for FCI</Note>
      </I18nProvider>,
    );
    expect(screen.getByText('Right pill').closest('.rt')).not.toBeNull();
    expect(screen.getByText('Sub text')).toBeTruthy();
    expect(container.querySelectorAll('.rt')).toHaveLength(1);
    expect(screen.getByText('Held for FCI').className).toBe('oq');
  });
});

describe('general reads', () => {
  it('SRS-G-13: the X-06 export carries the status only when one is chosen; the I-20 export has its path', () => {
    expect(generalApi.queriesExportUrl('OPEN')).toMatch(/\/v1\/queries\/export\?status=OPEN$/);
    expect(generalApi.queriesExportUrl()).toMatch(/\/v1\/queries\/export$/);
    expect(generalApi.offCycleExportUrl()).toMatch(/\/v1\/off-cycle\/export$/);
  });

  it('SRS l.1643: the items of a condition with no module ask for its code only', async () => {
    const calls = backend({ 'GET /v1/computation-exceptions/items': (url) => paged([], 0, url) });
    await generalPages.computationItems({ period: '2026-10' }, null, 'NO_PAN')(0, 20);
    expect(calls[0]?.url).toContain('code=NO_PAN');
    expect(calls[0]?.url).not.toContain('module=');
  });

  it('SRS-G-10: the I-20 and I-23 registers of every type read their first page', async () => {
    const calls = backend({
      'GET /v1/off-cycle': (url) => paged([], 0, url),
      'GET /v1/supplementary': (url) => paged([], 0, url),
    });
    await generalPages.offCycle(0, 20);
    await generalPages.supplementary(1, 50);
    expect(calls[0]?.url).toMatch(/\/v1\/off-cycle.*page=0.*size=20/);
    expect(calls[1]?.url).toMatch(/\/v1\/supplementary.*page=1.*size=50/);
  });
});

describe('X-06 the Checker on a query about a computed amount', () => {
  it('SRS-22-5: the Checker’s ✔ on an answered query about a computed amount opens it to confirm', async () => {
    const query = {
      id: 3,
      reference: 'QRY-00000003',
      payrollId: 88,
      eis: '105220',
      memberName: 'Sourav Banerjee',
      payrollYymm: '202607',
      channel: 'STAFF',
      queryText: 'Why is HRA lower?',
      concernsComputedAmount: true,
      status: 'IN_PROGRESS',
      raisedBy: 'maker.delhi',
      raisedAt: '2026-10-20T05:00:00Z',
      makerResponse: 'City class changed',
      respondedBy: 'maker.delhi',
      respondedAt: '2026-10-20T06:00:00Z',
      resolutionText: null,
      resolvedBy: null,
      resolvedAt: null,
    };
    const calls = backend({
      'GET /v1/queries/counts': () => ({ OPEN: 0, IN_PROGRESS: 1, RESOLVED: 0, CLOSED: 0 }),
      'GET /v1/queries': (url) => paged([query], 1, url),
    });
    wrap(<PayrollQueries />, ['X-06:VIEW', 'X-06:APPROVE']);
    const row = await waitFor(() => {
      const found = document.querySelector<HTMLElement>('tr[data-reference="QRY-00000003"]');
      if (!found) throw new Error('no row');
      return found;
    });
    await userEvent.click(within(row).getByRole('button', { name: '✔' }));
    expect(await screen.findByText(t('x06.detail', ['QRY-00000003']))).toBeTruthy();
    expect(calls.some((c) => c.method !== 'GET')).toBe(false);
  });
});
