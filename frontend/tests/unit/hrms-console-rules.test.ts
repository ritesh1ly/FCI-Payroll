import { describe, expect, it } from 'vitest';
import {
  CONSOLE_REASON_MAX,
  outcomeTone,
  parseBody,
  parseQuery,
  reasonError,
  runBlocked,
} from '@/modules/integration/business/consoleRules';

/** X-03 HRMS API console rules (SRS-INT-6, SRS-INT-33): the boxes of the call and the pill of its outcome. */

describe('X-03 console rules', () => {
  it('SRS-INT-6: the outcome pill is ok, warn, info or bad', () => {
    expect(outcomeTone('ACCEPTED')).toBe('p-ok');
    expect(outcomeTone('REJECTED')).toBe('p-warn');
    expect(outcomeTone('INVALID_REQUEST')).toBe('p-info');
    expect(outcomeTone('FAILED_IN_BODY')).toBe('p-bad');
    expect(outcomeTone('UNREADABLE')).toBe('p-bad');
  });

  it('SRS-INT-6: an empty or blank query is no parameters', () => {
    expect(parseQuery('')).toEqual({ ok: true, value: {} });
    expect(parseQuery('  \n ')).toEqual({ ok: true, value: {} });
    expect(parseQuery('{}')).toEqual({ ok: true, value: {} });
  });

  it('SRS-INT-6: a query is a JSON object of text values', () => {
    expect(parseQuery('{"empId":"123","d":""}')).toEqual({ ok: true, value: { empId: '123', d: '' } });
    expect(parseQuery('{oops')).toEqual({ ok: false, error: 'x03.console.err.badJson' });
    expect(parseQuery('null')).toEqual({ ok: false, error: 'x03.console.err.queryNotObject' });
    expect(parseQuery('[]')).toEqual({ ok: false, error: 'x03.console.err.queryNotObject' });
    expect(parseQuery('"a"')).toEqual({ ok: false, error: 'x03.console.err.queryNotObject' });
    expect(parseQuery('5')).toEqual({ ok: false, error: 'x03.console.err.queryNotObject' });
    expect(parseQuery('{"a":1}')).toEqual({ ok: false, error: 'x03.console.err.queryNotString' });
    expect(parseQuery('{"a":"x","b":null}')).toEqual({ ok: false, error: 'x03.console.err.queryNotString' });
  });

  it('SRS-INT-6: an empty body is none, any other body is any valid JSON', () => {
    expect(parseBody('')).toEqual({ ok: true, value: null });
    expect(parseBody('   ')).toEqual({ ok: true, value: null });
    expect(parseBody('{"a":[1,2]}')).toEqual({ ok: true, value: { a: [1, 2] } });
    expect(parseBody('[1]')).toEqual({ ok: true, value: [1] });
    expect(parseBody('0')).toEqual({ ok: true, value: 0 });
    expect(parseBody('null')).toEqual({ ok: true, value: null });
    expect(parseBody('{')).toEqual({ ok: false, error: 'x03.console.err.badJson' });
  });

  it('SRS-INT-33: the reason is required, plain ASCII and at most 500 characters', () => {
    expect(CONSOLE_REASON_MAX).toBe(500);
    expect(reasonError('')).toBe('x03.console.err.reasonRequired');
    expect(reasonError('   \t')).toBe('x03.console.err.reasonRequired');
    expect(reasonError('Check the leave detail of a test employee')).toBeNull();
    expect(reasonError('café')).toBe('x03.console.err.reasonAscii');
    expect(reasonError('हिंदी')).toBe('x03.console.err.reasonAscii');
    expect(reasonError('a'.repeat(500))).toBeNull();
    expect(reasonError('a'.repeat(501))).toBe('x03.console.err.reasonLength');
    // The reason is trimmed before it is counted.
    expect(reasonError(` ${'a'.repeat(500)} `)).toBeNull();
  });

  it('SRS-INT-6: Run waits for an API, readable JSON and a valid reason, in that order', () => {
    const ok = { code: 'leave_detail', query: '{}', body: '', reason: 'Because' };
    expect(runBlocked(ok)).toBeNull();
    expect(runBlocked({ ...ok, code: '' })).toBe('x03.console.why.api');
    expect(runBlocked({ ...ok, query: '[' })).toBe('x03.console.err.badJson');
    expect(runBlocked({ ...ok, body: '{' })).toBe('x03.console.err.badJson');
    expect(runBlocked({ ...ok, reason: ' ' })).toBe('x03.console.err.reasonRequired');
    expect(runBlocked({ code: '', query: '[', body: '{', reason: '' })).toBe('x03.console.why.api');
    expect(runBlocked({ ...ok, query: '[', body: '{', reason: '' })).toBe('x03.console.err.badJson');
    expect(runBlocked({ ...ok, body: '{', reason: '' })).toBe('x03.console.err.badJson');
  });
});
