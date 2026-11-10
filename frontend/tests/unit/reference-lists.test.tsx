import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ReferenceLists } from '@/modules/configuration';
import { codeOf } from '@/modules/configuration/components/ReferenceLists';
import { backend, me, problem, sent, t, wrap } from './support/harness';

/**
 * M-16 Reference & Combo List Administration: a system list keeps its codes, an open list takes new
 * values, and every proposal goes to the HQ checker with a reason and the OTP (SRS-G-1, SRS-G-6).
 */

afterEach(() => vi.unstubAllGlobals());

const governed = (answer: unknown) => (_url: string, init?: RequestInit) =>
  (JSON.parse(String(init?.body)) as { otp?: unknown }).otp ? answer : problem(428, 'AUTH-428-OTP', [7]);

async function answerOtp(code = '123456') {
  await userEvent.type(await screen.findByLabelText(t('otp.code')), code);
  await userEvent.click(screen.getByRole('button', { name: t('otp.verify') }));
}

async function cancelOtp() {
  await screen.findByLabelText(t('otp.code'));
  await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: t('common.cancel') }));
  await waitFor(() => expect(screen.queryByLabelText(t('otp.code'))).toBeNull());
}

const value = (over: object = {}) => ({
  code: 'PAUSED',
  labelEn: 'Paused',
  labelHi: 'रोका गया',
  sortOrder: 3,
  effectiveFrom: '2026-04-01',
  effectiveTo: null,
  active: true,
  versionNo: 1,
  changedBy: 'system',
  changedAt: null,
  pending: null,
  ...over,
});
const pending = (over: object = {}) => ({
  approvalId: 41,
  op: 'CREATE',
  code: 'HELD',
  labelEn: 'Held',
  labelHi: null,
  sortOrder: null,
  effectiveFrom: null,
  effectiveTo: null,
  submittedBy: 'sysadmin@fci-demo.test',
  submittedAt: '2026-10-21T08:00:00Z',
  ...over,
});
const LISTS = [
  {
    code: 'JOB_STATUS',
    name: 'Job status',
    module: 'C-13',
    system: true,
    values: [
      value(),
      value({ code: 'ENDED', labelHi: null, sortOrder: null, active: false, effectiveTo: '2026-05-01' }),
      value({ code: 'LATER', active: false, effectiveFrom: '2099-01-01' }),
      value({ code: 'LOCKED', pending: pending({ op: 'EDIT', approvalId: 40, code: 'LOCKED' }) }),
    ],
    pendingNew: [],
  },
  {
    code: 'LEAVE_TYPE',
    name: 'Leave type',
    module: null,
    system: false,
    values: [value({ code: 'EL', labelEn: 'Earned leave' })],
    pendingNew: [
      pending(),
      pending({
        approvalId: 42,
        code: 'CL',
        effectiveFrom: '2026-11-01',
        effectiveTo: '2027-03-31',
        labelHi: 'आकस्मिक',
        sortOrder: 2,
      }),
    ],
  },
];
const ALL = ['M-16:VIEW', 'M-16:CREATE', 'M-16:EDIT'];

function routes(
  post: (url: string, init?: RequestInit) => unknown = governed({ status: 202, body: pending() }),
) {
  return backend({
    'GET /v1/masters/reference-lists': () => LISTS,
    'POST /v1/masters/reference-lists/': post,
  });
}

describe('M-16 reference lists', () => {
  it('SRS-G-6: a system list keeps its codes; values show their state, Hindi label and pending change', async () => {
    routes();
    wrap(<ReferenceLists />, ALL);
    expect(await screen.findByText(t('m16.systemList'))).toBeTruthy();
    expect(screen.getByText(t('m16.systemHint'))).toBeTruthy();
    // M16-12, SRS-G-25: drawn disabled with the reason, no longer hidden.
    expect(screen.getByRole('button', { name: t('m16.newValue') })).toHaveAttribute(
      'data-why',
      t('m16.systemHint'),
    );
    expect(screen.getAllByText(t('m16.state.active'))).toHaveLength(2);
    expect(screen.getByText(t('m16.state.ended'))).toBeTruthy();
    expect(screen.getByText(t('m16.state.later'))).toBeTruthy();
    expect(screen.getByText(t('m16.noHindi'))).toBeTruthy();
    expect(screen.getByText(t('m16.pending', [40]))).toBeTruthy();
    // The value with a pending change cannot be changed again: its Change control stays visible,
    // disabled with the reason naming the pending request (SRS-G-4 / SRS-G-25, BMAD TR-004).
    const changes = screen.getAllByRole('button', { name: t('m16.change') });
    expect(changes).toHaveLength(4);
    const held = changes.filter((b) => (b as HTMLButtonElement).disabled);
    expect(held).toHaveLength(1);
    expect(held[0]?.getAttribute('title')).toBe(t('m16.heldByPending', [40]));
    expect(changes.filter((b) => b.getAttribute('title') === null)).toHaveLength(3);
  });

  it('SRS-16-15: an administrator outside Headquarters sees the changes disabled with the reason', async () => {
    routes();
    wrap(<ReferenceLists />, me(ALL, { atHeadquarters: false, hqOnlyScreens: ['M-16'] }));
    expect(await screen.findByText(t('m16.systemList'))).toBeTruthy();
    for (const button of screen.getAllByRole('button', { name: t('m16.change') })) {
      expect(button).toBeDisabled();
      expect(button).toHaveAttribute('title', t('error.AUTHZ-403-HQ-ONLY'));
    }
    await userEvent.selectOptions(screen.getByLabelText(t('m16.list')), 'LEAVE_TYPE');
    const newValue = screen.getByRole('button', { name: t('m16.newValue') });
    expect(newValue).toBeDisabled();
    expect(newValue).toHaveAttribute('data-why', t('error.AUTHZ-403-HQ-ONLY'));
  });

  it('SRS-G-1: an open list takes a new value, proposed with a reason and the OTP; pending new values are listed', async () => {
    const calls = routes();
    wrap(<ReferenceLists />, ALL);
    await screen.findByText(t('m16.systemList'));
    await userEvent.selectOptions(screen.getByLabelText(t('m16.list')), 'LEAVE_TYPE');
    expect(screen.getByText(t('m16.openList'))).toBeTruthy();
    expect(screen.getByText(t('m16.pendingNew', [41]))).toBeTruthy();
    expect(screen.getByText(t('m16.pendingNew', [42]))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('m16.newValue') }));
    expect(screen.getByText(t('m16.newValueOf', ['LEAVE_TYPE']))).toBeTruthy();
    const submit = screen.getByRole('button', { name: t('common.submitForApproval') }) as HTMLButtonElement;
    await userEvent.type(screen.getByLabelText(t('m16.code'), { selector: '#m16-code' }), 'ml');
    await userEvent.type(
      screen.getByLabelText(t('m16.labelEn'), { selector: '#m16-en' }),
      ' Maternity leave ',
    );
    await userEvent.type(screen.getByLabelText(t('m16.labelHi'), { selector: '#m16-hi' }), 'प्रसूति अवकाश');
    await userEvent.type(screen.getByLabelText(t('m16.order'), { selector: '#m16-order' }), '4');
    expect(submit.disabled).toBe(true);
    await userEvent.type(screen.getByLabelText(t('m16.reason'), { selector: '#m16-reason' }), ' Policy ');
    await userEvent.clear(screen.getByLabelText(t('m16.from'), { selector: '#m16-from' }));
    await userEvent.type(screen.getByLabelText(t('m16.from'), { selector: '#m16-from' }), '2099-01-01');
    await userEvent.type(screen.getByLabelText(t('m16.to'), { selector: '#m16-to' }), '2099-12-31');
    await userEvent.click(submit);
    await answerOtp();
    expect(await screen.findByText(t('m16.submitted', ['LEAVE_TYPE']))).toBeTruthy();
    expect(sent(calls, 'POST', '/LEAVE_TYPE/values').at(-1)).toEqual({
      code: 'ML',
      labelEn: 'Maternity leave',
      labelHi: 'प्रसूति अवकाश',
      sortOrder: 4,
      effectiveFrom: '2099-01-01',
      effectiveTo: '2099-12-31',
      reason: 'Policy',
      otp: { challengeId: 7, code: '123456' },
    });
  });

  it('a change keeps the code, may clear the Hindi label and order, and ends a value', async () => {
    const calls = routes();
    wrap(<ReferenceLists />, ALL);
    await screen.findByText(t('m16.systemList'));
    await userEvent.click(screen.getAllByRole('button', { name: t('m16.change') })[0] as HTMLElement);
    expect(screen.getByText(t('m16.changeOf', ['JOB_STATUS', 'PAUSED']))).toBeTruthy();
    expect(
      (screen.getByLabelText(t('m16.code'), { selector: '#m16-code' }) as HTMLInputElement).disabled,
    ).toBe(true);
    expect(screen.queryByLabelText(t('m16.from'), { selector: '#m16-from' })).toBeNull();
    await userEvent.clear(screen.getByLabelText(t('m16.labelHi'), { selector: '#m16-hi' }));
    await userEvent.clear(screen.getByLabelText(t('m16.order'), { selector: '#m16-order' }));
    await userEvent.type(screen.getByLabelText(t('m16.to'), { selector: '#m16-to' }), '2099-06-30');
    await userEvent.type(screen.getByLabelText(t('m16.reason'), { selector: '#m16-reason' }), 'Retire');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    await answerOtp();
    await waitFor(() =>
      expect(sent(calls, 'POST', '/JOB_STATUS/values/PAUSED').at(-1)).toEqual({
        labelEn: 'Paused',
        labelHi: null,
        sortOrder: null,
        effectiveTo: '2099-06-30',
        reason: 'Retire',
        otp: { challengeId: 7, code: '123456' },
      }),
    );
  });

  it('a value without Hindi label or order opens empty; a refusal stays, a cancelled OTP does not; switching list closes the form', async () => {
    let refuse = true;
    routes((url, init) =>
      refuse ? governed(problem(409, 'REF-409-DUPLICATE'))(url, init) : problem(428, 'AUTH-428-OTP', [7]),
    );
    wrap(<ReferenceLists />, ALL);
    await screen.findByText(t('m16.systemList'));
    await userEvent.click(screen.getAllByRole('button', { name: t('m16.change') })[1] as HTMLElement);
    expect((screen.getByLabelText(t('m16.labelHi'), { selector: '#m16-hi' }) as HTMLInputElement).value).toBe(
      '',
    );
    expect(
      (screen.getByLabelText(t('m16.order'), { selector: '#m16-order' }) as HTMLInputElement).value,
    ).toBe('');
    await userEvent.type(screen.getByLabelText(t('m16.reason'), { selector: '#m16-reason' }), 'x');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    await answerOtp();
    expect(await screen.findByText(t('error.REF-409-DUPLICATE'))).toBeTruthy();
    refuse = false;
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    await cancelOtp();
    await userEvent.click(screen.getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByLabelText(t('m16.reason'), { selector: '#m16-reason' })).toBeNull();
    await userEvent.click(screen.getAllByRole('button', { name: t('m16.change') })[0] as HTMLElement);
    await userEvent.selectOptions(screen.getByLabelText(t('m16.list')), 'LEAVE_TYPE');
    expect(screen.queryByLabelText(t('m16.reason'), { selector: '#m16-reason' })).toBeNull();
  });

  it('OD_135_theDrawnAdministratorWithoutTheRightIsToldWhy', async () => {
    routes();
    wrap(<ReferenceLists />, me(['M-16:VIEW'], { roles: ['SYSTEM_ADMINISTRATOR'] }));
    await screen.findByText(t('m16.systemList'));
    expect(screen.getByRole('button', { name: t('m16w.save') })).toHaveAttribute(
      'data-guard',
      t('m16w.why.admin'),
    );
  });

  it('a viewer changes nothing', async () => {
    routes();
    wrap(<ReferenceLists />, ['M-16:VIEW']);
    await screen.findByText(t('m16.systemList'));
    // SRS-G-25: every control stays, disabled with the reason.
    for (const change of screen.getAllByRole('button', { name: t('m16.change') })) {
      expect(change).toHaveAttribute('data-why', t('m16w.why.admin'));
    }
    expect(screen.getByRole('button', { name: t('m16w.new') })).toHaveAttribute(
      'data-why',
      t('m16w.why.admin'),
    );
    expect(screen.getByRole('button', { name: t('m16w.save') })).toBeDisabled();
    await userEvent.selectOptions(screen.getByLabelText(t('m16.list')), 'LEAVE_TYPE');
    expect(screen.getByRole('button', { name: t('m16.newValue') })).toHaveAttribute(
      'data-why',
      t('m16w.why.admin'),
    );
  });

  const DRAWN = [
    {
      code: 'LEAVE_FREQUENCY',
      name: 'Leave frequency',
      module: 'LEAVE',
      system: false,
      values: [value({ code: 'MONTHLY', labelEn: 'Monthly' })],
      pendingNew: [pending({ approvalId: 50, code: 'WEEKLY', labelEn: 'Weekly' })],
    },
    ...LISTS,
  ];

  it('SRS-G-6: a drawn list is a panel of its values, the rest open from a closed section, and the tiles count all', async () => {
    backend({ 'GET /v1/masters/reference-lists': () => DRAWN });
    wrap(<ReferenceLists />, ALL);
    const panel = (await screen.findByRole('heading', { name: t('m16w.list.LEAVE_FREQUENCY') })).closest(
      '.card',
    ) as HTMLElement;
    expect(within(panel).getAllByText('Monthly').length).toBeGreaterThan(0);
    expect(within(panel).getAllByText('Weekly').length).toBeGreaterThan(0);
    expect(screen.getByText(t('m16w.others', [2]))).toBeTruthy();
    const lists = screen.getByText(t('m16w.tile.lists')).closest('.tile') as HTMLElement;
    expect(within(lists).getByText('3')).toBeTruthy();
    const values = screen.getByText(t('m16w.tile.values')).closest('.tile') as HTMLElement;
    expect(within(values).getByText('6')).toBeTruthy();
    // SC-04-0066: only the drawn lists are offered on the card; the code comes from the text.
    expect(
      within(screen.getByLabelText(new RegExp(`^${t('m16w.listName')}`))).getAllByRole('option'),
    ).toHaveLength(2);
    expect(codeOf(' Bi-Annually / twice ')).toBe('BI_ANNUALLY_TWICE');
  });

  it('SRS l.1451: the drawn card sends list, text, date and reason for approval, and a repeated text is refused', async () => {
    let refuse = true;
    const calls = backend({
      'GET /v1/masters/reference-lists': () => DRAWN,
      'POST /v1/masters/reference-lists/': governed(
        refuse
          ? problem(409, 'MST-409-REF-TEXT', ['LEAVE_FREQUENCY', 'Monthly'])
          : { status: 202, body: pending() },
      ),
    });
    wrap(<ReferenceLists />, ALL);
    await screen.findByRole('heading', { name: t('m16w.list.LEAVE_FREQUENCY') });
    // Drawn enabled: while incomplete it says what is missing and sends nothing (the pack validates on Save).
    const incomplete = screen.getByRole('button', { name: t('m16w.save') });
    expect(incomplete).toBeEnabled();
    expect(incomplete).toHaveAttribute('data-guard', t('m16w.why.incomplete'));
    await userEvent.click(incomplete);
    expect(await screen.findByText(t('m16w.why.incomplete'))).toBeTruthy();
    expect(sent(calls, 'POST', '/LEAVE_FREQUENCY/values')).toHaveLength(0);
    await userEvent.selectOptions(
      screen.getByLabelText(new RegExp(`^${t('m16w.listName')}`)),
      'LEAVE_FREQUENCY',
    );
    await userEvent.type(screen.getByLabelText(new RegExp(`^${t('m16w.value')}`)), 'Bi-Annually');
    await userEvent.type(screen.getByLabelText(new RegExp(`^${t('m16w.reason')}`)), 'HR circular');
    const save = screen.getByRole('button', { name: t('m16w.save') });
    expect(save).toBeEnabled();
    expect(save).not.toHaveAttribute('data-guard');
    await userEvent.click(save);
    await answerOtp();
    expect(await screen.findByText(t('error.MST-409-REF-TEXT', ['LEAVE_FREQUENCY', 'Monthly']))).toBeTruthy();
    refuse = false;
    expect(sent(calls, 'POST', '/LEAVE_FREQUENCY/values').at(-1)).toMatchObject({
      code: 'BI_ANNUALLY',
      labelEn: 'Bi-Annually',
      reason: 'HR circular',
    });
  });

  it('a refused load is shown as the error', async () => {
    backend({ 'GET /v1/masters/reference-lists': () => problem(403, 'AUTH-403-FORBIDDEN') });
    wrap(<ReferenceLists />, ['M-16:VIEW']);
    expect(await screen.findByText(t('error.AUTH-403-FORBIDDEN'))).toBeTruthy();
  });
});
