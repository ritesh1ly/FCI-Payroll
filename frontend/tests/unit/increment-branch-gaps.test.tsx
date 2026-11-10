import { fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { IncrementFixation, PayScales } from '@/modules/increment';
import { backend, me, paged, t, wrap } from './support/harness';

/**
 * I-05 and M-03 at their branch edges (SRS-13-18, SRS l.3949, SC-17-0031): a revised Basic typed and
 * cleared is none again, an order copy cleared blocks the submission, a bound typed and cleared is no
 * bound, a stage row that is not a positive amount is marked and named.
 */

afterEach(() => vi.unstubAllGlobals());

describe('I-05 case: branch edges', () => {
  it('SRS_13_18_aRevisedBasicClearedAndAnOrderCopyClearedBlockTheCase', async () => {
    backend({
      'GET /v1/document-types': () => [{ code: 'OTHER', name: 'Other document', referenceRequired: false }],
      'GET /v1/increments/due?': (url) => paged([], 0, url),
      'POST /v1/pay-fixation-cases/previews': () => ({
        eis: '109992',
        caseType: 'INDIVIDUAL_REVISION',
        effectiveDate: '2026-08-01',
        employeeId: 12,
        oldBasic: 50000,
        revisedBasic: 52000,
        oldDueDate: null,
        correctedDueDate: null,
        test: null,
      }),
      'GET /v1/basic-history?': () => [],
    });
    wrap(<IncrementFixation />, me(['I-05:VIEW', 'I-05:SUBMIT']));
    const preview = await screen.findByRole('button', { name: t('action.i05.preview') });
    await userEvent.type(screen.getAllByLabelText(t('m01.eis'))[0] as HTMLElement, '109992');
    await userEvent.selectOptions(screen.getByLabelText(t('i05.caseType')), 'INDIVIDUAL_REVISION');
    await userEvent.type(screen.getAllByLabelText(t('i05.effective'))[0] as HTMLElement, '2026-08-01');
    await userEvent.type(screen.getByLabelText(t('i05.orderNumber')), 'EST/17');
    await userEvent.type(screen.getByLabelText(t('i05.orderDate')), '2026-10-20');
    await userEvent.type(screen.getByLabelText(t('i05.authority')), 'GM (P)');
    const reasons = screen.getAllByLabelText(t('common.reason'));
    await userEvent.type(reasons[reasons.length - 1] as HTMLElement, 'Order');
    const basic = screen.getAllByLabelText(t('i05.revisedBasic'))[0] as HTMLElement;
    await userEvent.type(basic, '52000');
    expect(preview).toBeEnabled();
    // A Basic typed and cleared is no Basic: the case is incomplete again.
    await userEvent.clear(basic);
    expect(preview).toBeDisabled();
    expect(preview.getAttribute('title')).toBe(t('i05.why.case'));
    await userEvent.type(basic, '52000');
    await userEvent.click(preview);
    expect(await screen.findByRole('region', { name: t('i05.proposal') })).toBeTruthy();
    const submitCase = screen.getByRole('button', { name: t('common.submitForApproval') });
    await userEvent.selectOptions(screen.getByLabelText(t('ord.type')), 'OTHER');
    const copy = screen.getByLabelText(t('i05.orderCopy'));
    await userEvent.upload(copy, new File(['%PDF-1.7'], 'order.pdf', { type: 'application/pdf' }));
    expect(submitCase).toBeEnabled();
    fireEvent.change(copy, { target: { files: [] } });
    expect(submitCase).toBeDisabled();
    expect(submitCase.getAttribute('title')).toBe(t('i05.why.copy'));
  });
});

const SCALE = {
  bandId: 3,
  code: 'E-4',
  name: 'IDA E-4',
  scaleType: 'IDA',
  min: 60000,
  max: 180000,
  stagnationAmount: null,
  effectiveFrom: '2017-01-01',
  stages: 37,
  pending: false,
  active: true,
};

describe('M-03 version: branch edges', () => {
  it('SC_17_0031_aBoundClearedIsNoBoundAndABadStageRowIsMarkedAndNamed', async () => {
    backend({
      'GET /v1/pay-scales?': (url) => paged([SCALE], 1, url),
      'GET /v1/pay-scales/E-4/stages': () => [60000, 62400],
      'GET /v1/masters/circulars/current': () => [{ id: 3, number: 'HQ/PAY/2026', subject: 'Scales' }],
      'GET /v1/pay-scales/wage-revisions': () => [
        { code: 'WR-2026-01', name: 'Staff pay revision', payrollTypes: 'STAFF_IDA', status: 'DEFINED' },
      ],
    });
    wrap(<PayScales />, me(['M-03:VIEW', 'M-03:CREATE']));
    const table = await screen.findByRole('table', { name: t('screen.M-03') });
    await userEvent.click(within(table).getByRole('button', { name: t('common.view') }));
    await screen.findByRole('list', { name: t('m03.stageGrid') });
    const propose = screen.getByRole('button', { name: t('action.newVersion') });
    const min = screen.getAllByLabelText(t('m03.min'))[0] as HTMLElement;
    await userEvent.type(min, '60000');
    await userEvent.type(screen.getAllByLabelText(t('m03.max'))[0] as HTMLElement, '70000');
    await userEvent.type(screen.getAllByLabelText(t('m03.from'))[0] as HTMLElement, '2027-01-01');
    await userEvent.selectOptions(
      screen.getByLabelText(t('m03.circular')),
      await screen.findByRole('option', { name: /HQ\/PAY\/2026/ }),
    );
    await userEvent.selectOptions(
      screen.getByLabelText(t('m03.wageRevision')),
      await screen.findByRole('option', { name: 'WR-2026-01 - Staff pay revision' }),
    );
    const row = screen.getByLabelText(t('m03.stageRow', [1]));
    await userEvent.type(row, '60000');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Revision');
    expect(propose).toBeEnabled();
    // A minimum typed and cleared is no minimum.
    await userEvent.clear(min);
    expect(propose).toBeDisabled();
    await userEvent.type(min, '60000');
    expect(propose).toBeEnabled();
    // SRS l.3949: a row that is not a positive amount is marked and named, and blocks the proposal.
    expect(row).not.toHaveAttribute('aria-invalid');
    expect(screen.queryByText(t('m03.why.stageRow', [1]))).toBeNull();
    await userEvent.clear(row);
    await userEvent.type(row, 'abc');
    expect(row).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByText(t('m03.why.stageRow', [1]))).toBeTruthy();
    expect(propose).toBeDisabled();
  });

  it('SRS_l3949_aStageRowIsRemovedAndTheLastRowCannotBe', async () => {
    backend({
      'GET /v1/pay-scales?': (url) => paged([SCALE], 1, url),
      'GET /v1/pay-scales/E-4/stages': () => [60000, 62400],
      'GET /v1/masters/circulars/current': () => [],
      'GET /v1/pay-scales/wage-revisions': () => [],
    });
    wrap(<PayScales />, me(['M-03:VIEW', 'M-03:CREATE']));
    const table = await screen.findByRole('table', { name: t('screen.M-03') });
    await userEvent.click(within(table).getByRole('button', { name: t('common.view') }));
    await screen.findByRole('list', { name: t('m03.stageGrid') });
    const remove = (n: number) => screen.getByRole('button', { name: t('m03.removeStage', [n]) });
    // One row only: the removal is disabled (a version needs at least one stage).
    expect(remove(1)).toBeDisabled();
    await userEvent.click(screen.getByRole('button', { name: t('m03.addStage') }));
    await userEvent.type(screen.getByLabelText(t('m03.stageRow', [1])), '60000');
    await userEvent.type(screen.getByLabelText(t('m03.stageRow', [2])), '62400');
    expect(remove(1)).toBeEnabled();
    await userEvent.click(remove(1));
    expect(screen.queryByLabelText(t('m03.stageRow', [2]))).toBeNull();
    expect(screen.getByLabelText(t('m03.stageRow', [1]))).toHaveValue('62400');
    expect(remove(1)).toBeDisabled();
  });
});
