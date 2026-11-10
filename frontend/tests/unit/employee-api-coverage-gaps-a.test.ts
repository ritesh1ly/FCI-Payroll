import { afterEach, describe, expect, it, vi } from 'vitest';
import { registerApi } from '@/modules/employee/api/registerApi';
import { backend, paged } from './support/harness';

/**
 * The Employee register reads (PENDING API-16-8): the M-01 export carries only the filters set (SRS-G-13), the I-21
 * register search is sent encoded (SRS-1-71), and the order list asks for the configured default size unless a size
 * is given (SRS-NF-3, COM-400-PAGE).
 */

afterEach(() => vi.unstubAllGlobals());

describe('registerApi', () => {
  it('SRS-G-13: the M-01 export URL carries the filters set and drops the empty ones', () => {
    const url = registerApi.masterExportUrl({ q: 'Sharma', payPattern: 'IDA', status: '', office: 'DH001' });
    expect(url).toMatch(/\/v1\/employees\/export\?q=Sharma&payPattern=IDA&office=DH001$/);
    expect(registerApi.masterExportUrl({ q: '', payPattern: '', status: '' })).toMatch(
      /\/v1\/employees\/export\?$/,
    );
  });

  it('SRS-1-71: the register search is encoded; an empty search sends no q', async () => {
    const calls = backend({ 'GET /v1/hrms-change-tasks/register': (url) => paged([], 0, url) });
    await registerApi.register('R&D Sharma', 1, 50);
    await registerApi.register('', 0, 20);
    expect(calls[0]?.url).toMatch(/\/v1\/hrms-change-tasks\/register\?q=R%26D%20Sharma&page=1&size=50$/);
    expect(calls[1]?.url).toMatch(/\/v1\/hrms-change-tasks\/register\?page=0&size=20$/);
  });

  it('SRS-NF-3: the order list sends a size only when one is given', async () => {
    const calls = backend({ 'GET /v1/hrms-change-orders': (url) => paged([], 0, url) });
    await registerApi.orderList(2, 100);
    await registerApi.orderList(0);
    expect(calls[0]?.url).toMatch(/\/v1\/hrms-change-orders\?page=2&size=100$/);
    expect(calls[1]?.url).toMatch(/\/v1\/hrms-change-orders\?page=0$/);
  });

  it('SRS-1-70: an EIS with spaces or reserved characters is trimmed and encoded in the path', async () => {
    const calls = backend({ 'GET /v1/employees/': () => ({}) });
    await registerApi.extras(' 10/48 ');
    expect(calls[0]?.url).toMatch(/\/v1\/employees\/10%2F48\/hrms-record$/);
  });
});
