import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { EsevaSupport } from '@/modules/eseva';
import { backend, me, paged, t, wrap } from './support/harness';

/**
 * E-02 support preview beyond the main path (SRS-17-10): a locked month the server names in
 * another form than YYYYMM is shown as it came, never cut into a wrong month.
 */

afterEach(() => vi.unstubAllGlobals());

describe('E-02 support preview (branch gaps)', () => {
  it('SRS-17-10: a payslip month not in YYYYMM form is shown as given', async () => {
    backend({
      'GET /v1/eseva/submissions?': (url) => paged([], 0, url),
      'POST /v1/eseva/support-previews': () => ({
        status: 201,
        body: {
          sessionId: 32,
          openedAt: '2026-10-28T05:00:00Z',
          submitEnabled: false,
          member: {
            eis: '104883',
            name: 'Meena Iyer',
            office: null,
            employeeType: 'EMPLOYEE',
            employmentStatus: 'IN_SERVICE',
            panMasked: null,
            aadhaarMasked: null,
            bankAccountMasked: null,
            mobileMasked: null,
            emailMasked: null,
          },
          payslipMonths: [
            { yymm: '2026-09', gross: 50000, deductions: 8000, net: 42000 },
            { yymm: '202608', gross: 50000, deductions: 8000, net: 42000 },
          ],
          submissions: [],
        },
      }),
    });
    wrap(<EsevaSupport />, me(['E-02:VIEW', 'E-02:SUPPORT']));
    await userEvent.type(screen.getAllByLabelText(t('m01.eis'))[0] as HTMLElement, '104883');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Ticket 43');
    await userEvent.click(screen.getByRole('button', { name: t('action.e02.preview') }));
    const view = await screen.findByRole('region', { name: t('e02.previewOf', ['104883']) });
    expect(within(view).getByText('2026-09')).toBeTruthy();
    expect(within(view).getByText('08/2026')).toBeTruthy();
  });
});
