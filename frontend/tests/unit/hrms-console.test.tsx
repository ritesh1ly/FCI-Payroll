import { fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { IntegrationMonitor } from '@/modules/integration';
import { HrmsConsole } from '@/modules/integration/components/HrmsConsole';
import type { HrmsApiRow, HrmsCallResult } from '@/modules/integration/api/integrationApi';
import { backend, me, paged, problem, referenceLists, sent, t, wrap } from './support/harness';

/**
 * X-03 HRMS API console (SRS-INT-6, SRS-INT-33): the Administrator opens it with "Test connection"
 * (Wireframes v6.1, OD-121) and calls a configured outbound HRMS API with a query, a body and a reason
 * and reads the outcome; every other role finds Test connection locked with the reason, and the console
 * itself is disabled with the reason for a non-Administrator.
 */

afterEach(() => vi.unstubAllGlobals());

function api(over: Partial<HrmsApiRow> = {}): HrmsApiRow {
  return {
    code: 'leave_detail',
    method: 'GET',
    selectionClass: 'REQUIRED',
    direction: 'OUTBOUND',
    configured: true,
    path: '/hrms/leave',
    interfaceCode: null,
    retryCount: null,
    backoffSeconds: null,
    backoffStrategy: null,
    timeoutSeconds: null,
    bulkheadMaxConcurrentCalls: null,
    bulkheadMaxWaitMillis: null,
    lastCallAt: null,
    lastStatus: 'NONE',
    received: null,
    accepted: null,
    rejected: null,
    refused: null,
    specSignedOff: false,
    days: 7,
    ...over,
  };
}

function result(over: Partial<HrmsCallResult> = {}): HrmsCallResult {
  return {
    code: 'leave_detail',
    method: 'GET',
    outcome: 'ACCEPTED',
    httpStatus: 200,
    errorCode: null,
    message: null,
    problems: [],
    duplicate: false,
    attempts: 1,
    elapsedMillis: 42,
    data: '{"empId":"****23"}',
    ...over,
  };
}

const LEAVE = `${t('x03.hrmsApi.leave_detail')} (GET /hrms/leave)`;

function setup(answer: () => unknown, options?: HrmsApiRow[]) {
  const rows = options ?? [api(), api({ code: 'cancel_leave_detail', method: 'POST', path: null })];
  return backend({
    'GET /v1/integration/hrms-apis?': (url) => paged(rows, rows.length, url),
    'POST /v1/integration/hrms-apis/': answer,
    'GET /v1/integration/': (url) => paged([], 0, url),
    'GET /v1/reference/lists': () => referenceLists({ INTERFACE_RUN_STATUS: [['OK', 'OK']] }),
  });
}

/** The screen as the Administrator, with the console opened from "Test connection". */
async function openAsAdmin() {
  wrap(<IntegrationMonitor />, ADMIN);
  await userEvent.click(screen.getByRole('button', { name: t('x03.testAct') }));
}

function consoleCard() {
  return within(screen.getByTestId('x03-hrms-console'));
}

async function fill(reason: string) {
  const card = consoleCard();
  await card.findByRole('option', { name: LEAVE });
  await userEvent.selectOptions(card.getByLabelText(t('x03.console.api')), 'leave_detail');
  if (reason) await userEvent.type(card.getByLabelText(t('x03.console.reason')), reason);
}

function setText(el: HTMLElement, value: string) {
  fireEvent.change(el, { target: { value } });
}

const ADMIN = me(['X-03:VIEW', 'X-03:EDIT']);

describe('X-03 HRMS API console', () => {
  it('SRS-INT-6: the API list is the configured outbound APIs, one page of 100, labelled with method and path', async () => {
    const calls = setup(() => result());
    await openAsAdmin();
    const card = consoleCard();
    expect(card.getByRole('heading', { name: t('x03.console.title') })).toBeTruthy();
    expect(await card.findByRole('option', { name: LEAVE })).toBeTruthy();
    expect(
      card.getByRole('option', { name: `${t('x03.hrmsApi.cancel_leave_detail')} (POST )` }),
    ).toBeTruthy();
    const listing = calls.find((c) => c.url.includes('direction=OUTBOUND&configured=true'));
    expect(listing?.url).toContain('size=100');
    expect(listing?.url).toContain('page=0');
  });

  it('SRS-INT-6: no configured outbound API says so', async () => {
    setup(() => result(), []);
    await openAsAdmin();
    expect(await consoleCard().findByText(t('x03.console.noApis'))).toBeTruthy();
  });

  it('SRS-INT-33: any other role finds Test connection locked with the reason; the console is disabled with the reason', async () => {
    const calls = setup(() => result());
    const view = wrap(<IntegrationMonitor />, me(['X-03:VIEW']));
    const test = screen.getByRole('button', { name: t('x03.testAct') });
    expect(test).toBeDisabled();
    expect(test.getAttribute('data-why')).toBe(t('x03.why.admin'));
    expect(screen.queryByTestId('x03-hrms-console')).toBeNull();
    view.unmount();
    wrap(<HrmsConsole isAdmin={false} />, me(['X-03:VIEW']));
    const card = consoleCard();
    expect(card.getByRole('group').getAttribute('title')).toBe(t('x03.reason.adminOnly'));
    const run = card.getByRole('button', { name: t('x03.console.run') });
    expect(run).toBeDisabled();
    expect(run.getAttribute('title')).toBe(t('x03.reason.adminOnly'));
    expect(card.getByLabelText(t('x03.console.api'))).toBeDisabled();
    expect(card.getByLabelText(t('x03.console.reason'))).toBeDisabled();
    expect(card.getByLabelText(t('x03.console.query'))).toBeDisabled();
    expect(card.getByLabelText(t('x03.console.body'))).toBeDisabled();
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
  });

  it('SRS-INT-6: Run waits for an API, readable JSON and a valid reason, and says why', async () => {
    const calls = setup(() => result());
    await openAsAdmin();
    const card = consoleCard();
    const run = card.getByRole('button', { name: t('x03.console.run') });
    expect(run).toBeDisabled();
    expect(run.getAttribute('title')).toBe(t('x03.console.why.api'));
    await fill('');
    expect(run.getAttribute('title')).toBe(t('x03.console.err.reasonRequired'));
    const query = card.getByLabelText(t('x03.console.query'));
    setText(query, '[1]');
    expect(card.getByText(t('x03.console.err.queryNotObject'))).toBeTruthy();
    expect(run).toBeDisabled();
    setText(query, '{"a":1}');
    expect(card.getByText(t('x03.console.err.queryNotString'))).toBeTruthy();
    setText(query, '{oops');
    expect(card.getByText(t('x03.console.err.badJson'))).toBeTruthy();
    setText(query, '{}');
    const body = card.getByLabelText(t('x03.console.body'));
    setText(body, '{');
    expect(card.getByText(t('x03.console.err.badJson'))).toBeTruthy();
    setText(body, '');
    const reason = card.getByLabelText(t('x03.console.reason'));
    await userEvent.type(reason, 'café');
    expect(card.getByText(t('x03.console.err.reasonAscii'))).toBeTruthy();
    expect(run).toBeDisabled();
    await userEvent.clear(reason);
    await userEvent.type(reason, 'Check');
    expect(run).toBeEnabled();
    expect(run.getAttribute('title')).toBeNull();
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
  });

  it('SRS-INT-6: an accepted call sends the query, body and trimmed reason exactly and shows the masked data', async () => {
    const calls = setup(() => result());
    await openAsAdmin();
    const card = consoleCard();
    await fill('  Check a leave  ');
    const query = card.getByLabelText(t('x03.console.query'));
    setText(query, '{"empId":"123"}');
    setText(card.getByLabelText(t('x03.console.body')), '{"k":[1,2]}');
    await userEvent.click(card.getByRole('button', { name: t('x03.console.run') }));
    const out = within(await screen.findByTestId('x03-hrms-console-result'));
    expect(sent(calls, 'POST', '/v1/integration/hrms-apis/leave_detail/call')).toEqual([
      { query: { empId: '123' }, body: { k: [1, 2] }, reason: 'Check a leave' },
    ]);
    expect(out.getByText(t('x03.console.outcome.ACCEPTED')).className).toContain('p-ok');
    expect(out.getByText('200')).toBeTruthy();
    expect(out.getByText('42')).toBeTruthy();
    expect(out.getByLabelText(t('x03.console.data')).textContent).toBe('{"empId":"****23"}');
    expect(out.queryByText(t('x03.console.duplicate'))).toBeNull();
    expect(out.queryByLabelText(t('x03.console.problems'))).toBeNull();
  });

  it('SRS-INT-6: an empty body is sent as none and the default query as an empty object', async () => {
    const calls = setup(() => result());
    await openAsAdmin();
    await fill('Why');
    await userEvent.click(consoleCard().getByRole('button', { name: t('x03.console.run') }));
    await screen.findByTestId('x03-hrms-console-result');
    expect(sent(calls, 'POST', '/call')).toEqual([{ query: {}, body: null, reason: 'Why' }]);
  });

  it.each([
    ['REJECTED', 'p-warn'],
    ['FAILED_IN_BODY', 'p-bad'],
    ['UNREADABLE', 'p-bad'],
    ['INVALID_REQUEST', 'p-info'],
  ] as const)(
    'SRS-INT-6: the outcome %s shows its pill, error, problems and duplicate badge',
    async (outcome, tone) => {
      setup(() =>
        result({
          outcome,
          httpStatus: null,
          errorCode: 'HRMS-123',
          message: 'The answer said no',
          problems: ['Field a is wrong', 'Field b is wrong'],
          duplicate: true,
          attempts: 3,
          data: null,
        }),
      );
      await openAsAdmin();
      await fill('Why');
      await userEvent.click(consoleCard().getByRole('button', { name: t('x03.console.run') }));
      const out = within(await screen.findByTestId('x03-hrms-console-result'));
      expect(out.getByText(t(`x03.console.outcome.${outcome}`)).className).toContain(tone);
      expect(out.getByText(t('x03.console.duplicate'))).toBeTruthy();
      expect(out.getByText('HRMS-123')).toBeTruthy();
      expect(out.getByText('The answer said no')).toBeTruthy();
      expect(out.getByText('3')).toBeTruthy();
      expect(out.getByText('—')).toBeTruthy();
      const list = out.getByLabelText(t('x03.console.problems'));
      expect(
        within(list)
          .getAllByRole('listitem')
          .map((li) => li.textContent),
      ).toEqual(['Field a is wrong', 'Field b is wrong']);
      expect(out.queryByLabelText(t('x03.console.data'))).toBeNull();
    },
  );

  it('SRS-INT-6: a result without status, error or message shows dashes', async () => {
    setup(() => result({ httpStatus: null, errorCode: null, message: null, data: null }));
    await openAsAdmin();
    await fill('Why');
    await userEvent.click(consoleCard().getByRole('button', { name: t('x03.console.run') }));
    const out = within(await screen.findByTestId('x03-hrms-console-result'));
    expect(out.getAllByText('—').length).toBe(3);
  });

  it('SRS-INT-6: a disabled console answers 403 and the problem is shown instead of a result', async () => {
    setup(() => problem(403, 'INT-403-CONSOLE-DISABLED'));
    await openAsAdmin();
    await fill('Why');
    const card = consoleCard();
    await userEvent.click(card.getByRole('button', { name: t('x03.console.run') }));
    expect(await card.findByRole('alert')).toBeTruthy();
    expect(screen.queryByTestId('x03-hrms-console-result')).toBeNull();
    expect(card.getByRole('button', { name: t('x03.console.run') })).toBeEnabled();
  });
});
