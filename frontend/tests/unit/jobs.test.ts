import { jobQuery, jobsApi } from '@/modules/jobs/api/jobsApi';
import { guardScreen } from '@/shared/server';
import type { Me } from '@/shared/session';

afterEach(() => vi.unstubAllGlobals());

const me = (permissions: string[]) => ({ permissions }) as unknown as Me;

describe('jobs API service', () => {
  it('builds the history query from the filter, leaving empty filters out', () => {
    expect(
      jobQuery({
        page: 2,
        size: 20,
        sort: 'startedAt',
        direction: 'asc',
        status: '',
        jobType: 'DA_ARREAR',
        requestedBy: '  maker ',
      }),
    ).toBe('jobType=DA_ARREAR&requestedBy=maker&page=2&size=20&sort=startedAt&direction=asc');
  });

  it('calls the right endpoints with the right methods and bodies', async () => {
    document.cookie = 'XSRF-TOKEN=t; Path=/';
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({}), { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);

    await jobsApi.list({ page: 0, size: 10, sort: 'requestedAt', direction: 'desc', status: 'RUNNING' });
    await jobsApi.detail(7);
    await jobsApi.skipped(7, 1, 10);
    await jobsApi.submit('PAYROLL_DRY_RUN', { locationId: '3', payPeriod: '2026-07' }, 5, 'July run');
    await jobsApi.control(7, 'RESTART', 'try again');
    await jobsApi.decide(77, 'REJECT', 'wrong period');

    const calls = fetchMock.mock.calls as unknown as [string, RequestInit][];
    expect(calls.map(([url, init]) => `${init.method} ${url}`)).toEqual([
      'GET /nginx/fci-payroll/v1/jobs?status=RUNNING&page=0&size=10&sort=requestedAt&direction=desc',
      'GET /nginx/fci-payroll/v1/jobs/7',
      'GET /nginx/fci-payroll/v1/jobs/7/skipped?page=1&size=10',
      'POST /nginx/fci-payroll/v1/jobs',
      'POST /nginx/fci-payroll/v1/jobs/7/restart',
      'POST /nginx/fci-payroll/v1/approvals/77/decision',
    ]);
    expect(JSON.parse(calls[3]?.[1].body as string)).toEqual({
      jobType: 'PAYROLL_DRY_RUN',
      params: { locationId: '3', payPeriod: '2026-07' },
      priority: 5,
      reason: 'July run',
    });
    expect(JSON.parse(calls[4]?.[1].body as string)).toEqual({ reason: 'try again' });
    expect(JSON.parse(calls[5]?.[1].body as string)).toEqual({ decision: 'REJECT', reason: 'wrong period' });
  });
});

describe('route guard', () => {
  it('redirects a guarded route on the server when the permission is missing (deny by default)', () => {
    expect(guardScreen(me(['C-02:VIEW']), 'C-13')).toBe('/payroll');
    expect(guardScreen(me(['C-13:VIEW']), 'C-13')).toBeNull();
    expect(guardScreen(me([]), 'M-01')).toBe('/payroll');
    expect(guardScreen(me([]), null)).toBeNull();
  });
});
