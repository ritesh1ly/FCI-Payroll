import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { NpsDeductionScreen } from '@/modules/nps';
import { businessToday } from '@/shared/time';
import { backend, me, paged, t, wrap } from './support/harness';

/**
 * Coverage of I-14's remaining paths (SRS-9-1, SRS-9-1a; OD-121): a session without a period reads this business month,
 * and a record received without office, PRAN or effective date reads a dash for each, its flag as No.
 */
afterEach(() => vi.unstubAllGlobals());

describe('I-14 remaining paths', () => {
  it('SRS_9_1_aSessionWithoutPeriodAsksForThisMonthAndAnEmptyRecordReadsDashes', async () => {
    const calls = backend({
      'GET /v1/nps-deductions/summary': () => ({
        yymm: '202601',
        members: 1,
        employeeAmount: 0,
        employerAmount: 0,
        mismatches: 1,
      }),
      'GET /v1/nps-deductions/counts': () => [],
      'GET /v1/nps-deductions?': (url: string) =>
        paged(
          [
            {
              id: 2,
              eis: '880613',
              employee: 'Debashis Paul',
              office: null,
              npsFlag: false,
              pran: null,
              employeeAmount: 6200,
              employerAmount: null,
              deducted: null,
              effectiveFrom: null,
              status: 'AMOUNT_WITHOUT_FLAG',
              payPattern: 'CDA',
              providentFund: 'GPF',
            },
          ],
          1,
          url,
        ),
    });
    const user = userEvent.setup();
    wrap(<NpsDeductionScreen />, me(['I-14:VIEW'], { period: '' }));
    const month = businessToday().slice(0, 7).replace('-', '');
    await waitFor(() =>
      expect(calls.some((c) => c.url.includes(`/v1/nps-deductions/summary?yymm=${month}`))).toBe(true),
    );
    await user.click(await screen.findByRole('button', { name: t('i14w.viewOf', ['880613']) }));
    const dialog = await screen.findByRole('dialog');
    const value = (label: string) =>
      (within(dialog).getByText(label).parentElement as HTMLElement).textContent;
    expect(value(t('i14w.col.office'))).toContain('—');
    expect(value(t('i14w.col.pran'))).toContain('—');
    expect(value(t('i14.effectiveFrom'))).toContain('—');
    expect(value(t('i14w.col.flag'))).toContain(t('i14w.flag.no'));
  });
});
