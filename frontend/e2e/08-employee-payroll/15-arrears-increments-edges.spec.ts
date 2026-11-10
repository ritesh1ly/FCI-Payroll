import { expect, test } from '@playwright/test';
import { apiProblem, t, workAs } from '../support/app';

/**
 * Edge cases of modules 13 and 17 (plans 13 and 17), driven through the API a crafted request would use and the screens'
 * disabled-with-reason states: every refusal is a named error, never a 500 and never a partial change.
 *
 * I-08: a blank remark, a bulk event computed again, an unknown event, a malformed filter. I-24: an empty selection, an
 * order that does not exist, a malformed EIS. I-25: a labour revision, a selected scope, a minimum above the maximum, a
 * revision that is not approved fixed in bulk. I-05: a malformed month, a case without its order, a revised Basic above
 * the scale maximum, an unknown member, an empty run. M-03: a stage outside the bounds, bounds out of order.
 */
const dh = { office: 'DH001' };

test.describe('Arrears, wage revision and increments: edge cases', () => {
  test(
    'SRS-21-9 / SRS-8-13: I-08 refuses a blank remark, a bulk recompute, an unknown event and a bad filter',
    { tag: ['@SRS-21-9', '@SRS-8-13', '@SRS-8-14', '@edge'] },
    async ({ page }) => {
      await workAs(page, 'en', 'maker.delhi@fci-demo.test', { role: 'PAYROLL_MAKER', ...dh }, 'I-08');
      const id = await page.evaluate(async () => {
        const r = await fetch('/nginx/fci-payroll/v1/arrear-events?size=100', { credentials: 'same-origin' });
        const body = (await r.json()) as { items: { id: number; reference: string }[] };
        return body.items.find((e) => e.reference === 'ARR-DEMO-DA-01')?.id;
      });
      expect(id).toBeDefined();
      const blank = await apiProblem(page, `/v1/arrear-events/${id}/submissions`, 'POST', { remark: '   ' });
      expect([422, 409]).toContain(blank.status);
      expect(['ARR-422-REMARK', 'ARR-409-STATE', 'WF-409-PENDING-BY']).toContain(blank.errorCode);
      expect((await apiProblem(page, `/v1/arrear-events/${id}/computations`, 'POST', {})).errorCode).toBe(
        'ARR-409-STATE',
      );
      expect((await apiProblem(page, '/v1/arrear-events/999999999/details')).status).toBe(404);
      expect((await apiProblem(page, '/v1/arrear-events?status=NOT_A_STATUS')).status).toBe(400);
    },
  );

  test(
    'SRS-8-24: I-24 refuses an empty selection, an unknown order and a malformed member',
    { tag: ['@SRS-8-24', '@SRS-8-23', '@edge'] },
    async ({ page }) => {
      await workAs(page, 'en', 'maker.delhi@fci-demo.test', { role: 'PAYROLL_MAKER', ...dh }, 'I-24');
      expect(
        (await apiProblem(page, '/v1/leave-encashment-arrears/submissions', 'POST', { ids: [], remark: 'x' }))
          .status,
      ).toBe(400);
      const unknown = await apiProblem(page, '/v1/leave-encashment-arrears/runs', 'POST', {
        eis: '104882',
        circularId: 999999999,
        revisionEffective: '2026-07-01',
        reason: 'Edge (E2E)',
      });
      expect(unknown.status).toBeGreaterThanOrEqual(400);
      expect(unknown.status).toBeLessThan(500);
      expect(
        (
          await apiProblem(page, '/v1/leave-encashment-arrears/runs', 'POST', {
            eis: '10488<2',
            circularId: 1,
            revisionEffective: '2026-07-01',
            reason: 'x',
          })
        ).status,
      ).toBe(400);
      await page.getByRole('button', { name: t('en', 'i24.act.submit') }).click();
      const submit = page.getByRole('button', { name: new RegExp(t('en', 'action.i24.submit')) });
      await expect(submit).toBeDisabled();
      await expect(submit).toHaveAttribute('title', t('en', 'i24.why.none'));
    },
  );

  test(
    'SRS-8-25: I-25 refuses a labour revision, a selected scope, bounds out of order and fixing an unapproved revision',
    { tag: ['@SRS-8-25', '@edge'] },
    async ({ page }) => {
      await workAs(
        page,
        'en',
        'su.hq@fci-demo.test',
        { role: 'RULE_ADMINISTRATOR', office: 'HQ001' },
        'I-25',
      );
      const base = {
        name: 'Edge (E2E)',
        circularId: 1,
        payrollTypes: ['STAFF_IDA'],
        effectiveFrom: '2030-04-01',
        fitment: 'PERCENTAGE',
        value: 3,
        rounding: 'RUPEE',
        reason: 'Edge (E2E)',
      };
      expect(
        (await apiProblem(page, '/v1/wage-revisions', 'POST', { ...base, payrollTypes: ['DPS_LABOUR'] }))
          .errorCode,
      ).toBe('WR-422-NOT-AVAILABLE');
      expect(
        (await apiProblem(page, '/v1/wage-revisions', 'POST', { ...base, scope: 'SELECTED' })).errorCode,
      ).toBe('WR-422-NOT-AVAILABLE');
      expect(
        (await apiProblem(page, '/v1/wage-revisions', 'POST', { ...base, minimum: 50000, maximum: 40000 }))
          .errorCode,
      ).toBe('WR-422-DEFINITION');
      expect((await apiProblem(page, '/v1/wage-revisions/999999999/fixations', 'POST', {})).status).toBe(404);
      expect((await apiProblem(page, '/v1/wage-revisions/999999999/simulations', 'POST', {})).status).toBe(
        404,
      );
    },
  );

  test(
    'SRS-13-18 / 13-23: I-05 refuses a bad month, a case without its order, a Basic above the maximum and an unknown member',
    { tag: ['@SRS-13-18', '@SRS-13-23', '@SRS-13-5', '@edge'] },
    async ({ page }) => {
      await workAs(page, 'en', 'hr.delhi@fci-demo.test', { role: 'ESTABLISHMENT_HR', ...dh }, 'I-05');
      expect((await apiProblem(page, '/v1/increments/due?month=2026-13')).status).toBe(400);
      const c = {
        eis: '109992',
        caseType: 'INDIVIDUAL_REVISION',
        effectiveDate: '2026-04-01',
        basic: 51000,
        orderNumber: 'EST/E2E/1',
        orderDate: '2026-04-01',
        issuingAuthority: 'GM (P)',
        reason: 'Edge (E2E)',
      };
      expect(
        (await apiProblem(page, '/v1/pay-fixation-cases/previews', 'POST', { ...c, orderNumber: ' ' }))
          .errorCode,
      ).toBe('INC-422-ENTRY');
      expect(
        (await apiProblem(page, '/v1/pay-fixation-cases/previews', 'POST', { ...c, basic: 9999999 }))
          .errorCode,
      ).toBe('INC-422-FLAGGED');
      expect(
        (await apiProblem(page, '/v1/pay-fixation-cases/previews', 'POST', { ...c, eis: '999998' })).status,
      ).toBe(404);
      expect(
        (await apiProblem(page, '/v1/increments/submissions', 'POST', { employeeIds: [], remark: 'x' }))
          .status,
      ).toBe(400);
      // The case entry lives in the closed I-05 register under the drawn screen (OD-121).
      const register = page.getByTestId('i05-register');
      if ((await register.getAttribute('open')) === null) await register.locator(':scope > summary').click();
      const preview = register.getByRole('button', { name: t('en', 'action.i05.preview'), exact: true });
      await expect(preview).toBeDisabled();
      await expect(preview).toHaveAttribute('title', t('en', 'i05.why.case'));
    },
  );

  test(
    'M-03: a version with a stage outside its bounds or bounds out of order is refused',
    { tag: ['@SRS-13-23', '@edge'] },
    async ({ page }) => {
      await workAs(
        page,
        'en',
        'su.hq@fci-demo.test',
        { role: 'RULE_ADMINISTRATOR', office: 'HQ001' },
        'M-03',
      );
      const v = {
        min: 40000,
        max: 140000,
        effectiveFrom: '2031-04-01',
        circularId: 1,
        stages: [40000, 41200],
        reason: 'Edge (E2E)',
      };
      expect(
        (await apiProblem(page, '/v1/pay-scales/E-2/versions', 'POST', { ...v, stages: [39000] })).errorCode,
      ).toBe('INC-422-ENTRY');
      expect(
        (await apiProblem(page, '/v1/pay-scales/E-2/versions', 'POST', { ...v, min: 150000 })).errorCode,
      ).toBe('INC-422-ENTRY');
      expect(
        (await apiProblem(page, '/v1/pay-scales/NO-SUCH/versions', 'POST', v)).status,
      ).toBeGreaterThanOrEqual(400);
    },
  );
});
