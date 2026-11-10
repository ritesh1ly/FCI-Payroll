import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { CeilingNormsScreen } from '@/modules/configuration';
import { date } from '@/shared/ui';
import { backend, me, paged, problem, referenceLists, sent, t, wrap } from './support/harness';

/**
 * M-15 Allowance and Reimbursement Ceiling Norms as drawn (SRS-16-14, SRS-11-15, SRS-G-38, SRS-G-48; OD-121): the
 * Rule Administrator's proposal with its circular (sent, refused, OTP cancelled), the drawn buttons that bring the
 * form into view, a row's Edit presetting its claim type, the unconfirmed and pending-only rows, the Views and the
 * tiles when the counts cannot be read.
 */

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const REFERENCE = referenceLists({
  CLAIM_TYPE: [
    ['MEDICAL', 'Medical reimbursement'],
    ['LABOUR_TA', 'Labour TA'],
    ['LTC', 'Leave Travel Concession'],
  ],
  CEILING_BASIS: [
    ['FIXED_AMOUNT', 'Fixed Amount'],
    ['PERCENT_BASIC_DA', 'Percentage of Basic + DA'],
  ],
  ELIGIBILITY_CATEGORY: [['DEMO_ALL', 'Demo - all members']],
  ALLOWANCE_PERIODICITY: [
    ['MONTHLY', 'Monthly'],
    ['ANNUAL', 'Annual'],
  ],
});

const version = (over: object = {}) => ({
  id: 1,
  versionNo: 1,
  effectiveFrom: '2026-04-01',
  effectiveTo: null,
  ceilingBasis: 'FIXED_AMOUNT',
  ceilingValue: 15000,
  periodicity: 'ANNUAL',
  eligibilityCategory: 'DEMO_ALL',
  memberScope: 'EMPLOYEE',
  confirmed: true,
  status: 'ACTIVE',
  approvalId: 1,
  circularOrderId: null,
  ...over,
});

const ROWS = [
  { claimType: 'MEDICAL', name: 'Medical', current: version({ confirmed: false }), pending: null },
  {
    claimType: 'LTC',
    name: 'LTC',
    current: null,
    pending: version({
      ceilingBasis: 'PERCENT_BASIC_DA',
      ceilingValue: 7,
      periodicity: 'MONTHLY',
      status: 'PENDING_APPROVAL',
    }),
  },
  {
    claimType: 'LABOUR_TA',
    name: 'Labour TA',
    current: version({ ceilingBasis: null, ceilingValue: 900 }),
    pending: null,
  },
];

function routes(over: Record<string, (url: string, init?: RequestInit) => unknown> = {}) {
  return backend({
    'GET /v1/reference/lists': () => REFERENCE,
    'GET /v1/masters/ceiling-norms/counts': () => ({ claimTypes: 3, percentageRules: 1, pendingApproval: 1 }),
    'GET /v1/masters/ceiling-norms': () => paged(ROWS),
    'GET /v1/claims/ceiling-breaches': () => 4,
    'GET /v1/masters/circulars/current': () => [{ id: 9, number: 'CIR-9', subject: 'Norms' }],
    ...over,
  });
}

const ADMIN = me(['M-15:VIEW', 'M-15:CREATE'], { roles: ['RULE_ADMINISTRATOR'] });

async function fill() {
  await screen.findByRole('cell', { name: 'Medical reimbursement' }, { timeout: 30000 });
  await userEvent.selectOptions(screen.getByLabelText(/Claim Type/), 'MEDICAL');
  await userEvent.selectOptions(screen.getByLabelText(/Ceiling Basis/), 'FIXED_AMOUNT');
  await userEvent.type(screen.getByLabelText(/Ceiling Value/), '2500');
  await userEvent.selectOptions(screen.getByLabelText(/Periodicity/), 'ANNUAL');
  await userEvent.selectOptions(screen.getByLabelText(/Eligibility Category/), 'DEMO_ALL');
  fireEvent.change(screen.getByLabelText(/Effective From/), { target: { value: '2027-04-01' } });
  await userEvent.type(screen.getByLabelText(/Reason for Change/), ' Annual revision ');
}

async function chooseCircular() {
  await screen.findByRole('option', { name: 'CIR-9 · Norms' }, { timeout: 30000 });
  await userEvent.selectOptions(screen.getByLabelText(t('mst.circular')), '9');
}

const save = () => screen.getByRole('button', { name: t('m15w.save') });

describe('M-15 proposal by the Rule Administrator', () => {
  it('SRS_G_38_saveIsLockedUntilTheCircularIsChosen_thenOffWhileIncomplete', async () => {
    routes();
    wrap(<CeilingNormsScreen />, ADMIN);
    await screen.findByRole('cell', { name: 'Medical reimbursement' }, { timeout: 30000 });
    await waitFor(() => expect(save()).toHaveAttribute('data-why', t('mst.why.circular')));
    await chooseCircular();
    expect(save()).toBeDisabled();
    expect(save()).toHaveAttribute('title', t('m15w.why.incomplete'));
    // Clearing the circular locks it again.
    await userEvent.selectOptions(screen.getByLabelText(t('mst.circular')), '');
    expect(save()).toHaveAttribute('data-why', t('mst.why.circular'));
  });

  it('SRS_16_14_everyDrawnFieldAndTheHiddenOnesAreSent_theFormEmpties', async () => {
    const calls = routes({ 'POST /v1/masters/ceiling-norms/': () => ({ status: 201, body: version() }) });
    wrap(<CeilingNormsScreen />, ADMIN);
    await fill();
    await userEvent.selectOptions(screen.getByLabelText(t('m15.memberScope')), 'EMPLOYEE');
    await userEvent.click(screen.getByLabelText(t('mst.confirmed')));
    await chooseCircular();
    expect(save()).toBeEnabled();
    expect(save()).not.toHaveAttribute('title');
    await userEvent.click(save());
    await waitFor(() =>
      expect(sent(calls, 'POST', '/ceiling-norms/MEDICAL/versions')).toEqual([
        {
          effectiveFrom: '2027-04-01',
          ceilingBasis: 'FIXED_AMOUNT',
          ceilingValue: 2500,
          periodicity: 'ANNUAL',
          eligibilityCategory: 'DEMO_ALL',
          memberScope: 'EMPLOYEE',
          confirmed: true,
          circularOrderId: 9,
          reason: 'Annual revision',
        },
      ]),
    );
    expect(await screen.findByText(t('mst.submitted', ['Medical reimbursement']))).toBeTruthy();
    expect(screen.getByLabelText(/Claim Type/)).toHaveValue('');
    expect(screen.getByLabelText(/Ceiling Value/)).toHaveValue(null);
  });

  it('SRS_16_14_aValueClearedOrZeroCannotBeSent', async () => {
    routes();
    wrap(<CeilingNormsScreen />, ADMIN);
    await fill();
    await chooseCircular();
    expect(save()).toBeEnabled();
    await userEvent.clear(screen.getByLabelText(/Ceiling Value/));
    expect(save()).toBeDisabled();
    await userEvent.type(screen.getByLabelText(/Ceiling Value/), '0');
    expect(save()).toBeDisabled();
    await userEvent.selectOptions(screen.getByLabelText(/Ceiling Basis/), '');
    expect(screen.getByText(t('m15w.valueHint'))).toBeTruthy();
  });

  it('SRS_16_14_aRefusedProposalShowsTheProblem_aCancelledOtpStaysSilent', async () => {
    let answer = problem(409, 'MST-409-OVERLAP');
    routes({ 'POST /v1/masters/ceiling-norms/': () => answer });
    wrap(<CeilingNormsScreen />, ADMIN);
    await fill();
    await chooseCircular();
    await userEvent.click(save());
    expect(await screen.findByText(t('error.MST-409-OVERLAP'))).toBeTruthy();
    answer = problem(428, 'AUTH-428-OTP', ['7']);
    await userEvent.click(save());
    const otp = await screen.findByRole('dialog', { name: t('otp.title') });
    await userEvent.click(within(otp).getByRole('button', { name: t('common.cancel') }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: t('otp.title') })).toBeNull());
    expect(screen.queryByText(t('error.MST-409-OVERLAP'))).toBeNull();
    expect(screen.getByLabelText(/Claim Type/)).toHaveValue('MEDICAL');
  });

  it('SRS_G_38_withoutAnActiveCircularTheFormSaysWhereToRecordIt', async () => {
    routes({ 'GET /v1/masters/circulars/current': () => [] });
    wrap(<CeilingNormsScreen />, ADMIN);
    expect(await screen.findByText(t('m27.noActiveCircular'))).toBeTruthy();
  });
});

describe('M-15 drawn buttons and rows', () => {
  it('SRS_16_14_newRuleSupersedeAndARowEditBringTheFormIntoView_theEditPresetsItsType', async () => {
    routes();
    wrap(<CeilingNormsScreen />, ADMIN);
    await screen.findByRole('cell', { name: 'Medical reimbursement' }, { timeout: 30000 });
    // Without scrolling support the buttons do nothing harmful.
    await userEvent.click(screen.getByRole('button', { name: t('m15w.newRule') }));
    const scroll = vi.fn();
    Element.prototype.scrollIntoView = scroll;
    try {
      await userEvent.click(screen.getByRole('button', { name: t('m15w.newRule') }));
      expect(scroll).toHaveBeenCalledWith({ behavior: 'smooth' });
      await userEvent.click(screen.getByRole('button', { name: t('m20.supersede') }));
      expect(scroll).toHaveBeenCalledTimes(2);
      expect(screen.getByLabelText(/Claim Type/)).toHaveValue('');
      await userEvent.click(screen.getByRole('button', { name: `${t('common.edit')} Labour TA` }));
      expect(scroll).toHaveBeenCalledTimes(3);
      expect(screen.getByLabelText(/Claim Type/)).toHaveValue('LABOUR_TA');
    } finally {
      delete (Element.prototype as { scrollIntoView?: unknown }).scrollIntoView;
    }
  });

  it('SRS_16_14_aPendingOnlyRowShowsTheProposalAndItsEditIsOffWithThePendingReason', async () => {
    routes();
    wrap(<CeilingNormsScreen />, ADMIN);
    const ltc = (await screen.findByRole('cell', { name: 'Leave Travel Concession' })).closest(
      'tr',
    ) as HTMLElement;
    expect(ltc).toHaveTextContent('7%');
    expect(ltc).toHaveTextContent('Percentage of Basic + DA');
    expect(within(ltc).getByText(t('status.PENDING_APPROVAL'))).toHaveClass('p-warn');
    const edit = within(ltc).getByRole('button', { name: `${t('common.edit')} Leave Travel Concession` });
    expect(edit).toBeDisabled();
    expect(edit).toHaveAttribute('data-why', t('mst.why.pending'));
    const medical = screen.getByRole('cell', { name: 'Medical reimbursement' }).closest('tr') as HTMLElement;
    expect(within(medical).getByText(t('mst.unconfirmed'))).toHaveClass('p-warn');
    const labour = screen.getByRole('cell', { name: 'Labour TA' }).closest('tr') as HTMLElement;
    expect(within(labour).getAllByText('—')).toHaveLength(1);
  });

  it('SRS_16_14_aViewerIsToldEditAndSupersedeAreTheAdministrators', async () => {
    routes();
    wrap(<CeilingNormsScreen />, ['M-15:VIEW']);
    await screen.findByRole('cell', { name: 'Medical reimbursement' }, { timeout: 30000 });
    expect(screen.getByRole('button', { name: t('m20.supersede') })).toHaveAttribute(
      'data-guard',
      t('m15w.why.admin'),
    );
    expect(screen.getByRole('button', { name: `${t('common.edit')} Labour TA` })).toHaveAttribute(
      'data-why',
      t('m15w.why.admin'),
    );
  });

  it('SRS_16_14_theViewsShowThePendingProposal_orNoBasis', async () => {
    routes();
    wrap(<CeilingNormsScreen />, ['M-15:VIEW']);
    await userEvent.click(
      await screen.findByRole('button', { name: `${t('common.view')} Leave Travel Concession` }),
    );
    let dialog = await screen.findByRole('dialog', { name: t('wf.viewHead', [t('screen.M-15')]) });
    expect(within(dialog).getByText('7%')).toBeTruthy();
    expect(within(dialog).getByText('Percentage of Basic + DA')).toBeTruthy();
    expect(within(dialog).getByText(t('m15.periodicity.MONTHLY'))).toBeTruthy();
    expect(within(dialog).getByText(t('m15.memberScope.EMPLOYEE'))).toBeTruthy();
    expect(within(dialog).getByText('Demo - all members')).toBeTruthy();
    expect(within(dialog).getByText(date('2026-04-01'))).toBeTruthy();
    await userEvent.click(within(dialog).getByRole('button', { name: t('common.close') }));
    await userEvent.click(screen.getByRole('button', { name: `${t('common.view')} Labour TA` }));
    dialog = await screen.findByRole('dialog', { name: t('wf.viewHead', [t('screen.M-15')]) });
    expect(within(dialog).getByText('—')).toBeTruthy();
  });

  it('SRS_16_14_theViewOfANormNeverConfiguredIsAllDashes', async () => {
    routes({
      'GET /v1/masters/ceiling-norms': () =>
        paged([{ claimType: 'LABOUR_TA', name: 'Labour TA', current: null, pending: null }]),
    });
    wrap(<CeilingNormsScreen />, ['M-15:VIEW']);
    await userEvent.click(await screen.findByRole('button', { name: `${t('common.view')} Labour TA` }));
    const dialog = await screen.findByRole('dialog', { name: t('wf.viewHead', [t('screen.M-15')]) });
    expect(within(dialog).getAllByText('—')).toHaveLength(6);
    expect(within(dialog).getByText(t('mst.notConfigured'))).toBeTruthy();
  });

  it('M08_08_theCheckerTickGoesToTheInboxOnlyForAPendingNorm', async () => {
    const assign = vi.fn();
    routes();
    vi.stubGlobal('location', { ...window.location, assign });
    wrap(<CeilingNormsScreen />, ['M-15:VIEW', 'M-15:APPROVE']);
    const ltc = (await screen.findByRole('cell', { name: 'Leave Travel Concession' })).closest(
      'tr',
    ) as HTMLElement;
    await userEvent.click(within(ltc).getByTitle(t('wf.approveTitle')));
    expect(assign).toHaveBeenCalledTimes(1);
    const medical = screen.getByRole('cell', { name: 'Medical reimbursement' }).closest('tr') as HTMLElement;
    await userEvent.click(within(medical).getByTitle(t('wf.approveTitle')));
    expect(await screen.findByText(t('mst.nothingWaiting'))).toBeTruthy();
    expect(assign).toHaveBeenCalledTimes(1);
  });

  it('SRS_16_14_tilesThatCannotBeReadShowADash', async () => {
    routes({
      'GET /v1/masters/ceiling-norms/counts': () => problem(500, 'SYS-500'),
      'GET /v1/claims/ceiling-breaches': () => problem(500, 'SYS-500'),
    });
    wrap(<CeilingNormsScreen />, ['M-15:VIEW']);
    await screen.findByRole('cell', { name: 'Medical reimbursement' }, { timeout: 30000 });
    for (const label of ['m15w.tile.types', 'm15w.tile.percent', 'm15w.tile.pending', 'm15w.tile.breaches']) {
      await waitFor(() =>
        expect(
          within(screen.getByText(t(label)).closest('.tile') as HTMLElement).getByText('—'),
        ).toBeTruthy(),
      );
    }
  });
});
