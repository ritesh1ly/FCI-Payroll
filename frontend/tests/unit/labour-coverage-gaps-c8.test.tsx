import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { LabourReports } from '@/modules/reporting';
import { backend, me, paged, t, wrap } from './support/harness';

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }));

/**
 * O-03 Labour Registers & Reports as drawn (Wireframes v6.1, OD-121; SRS-18-24, SRS-G-54, FRS Annexure A.4, A.11)
 * coverage gaps: an available entry and a blocked one without its reason, entries without payroll types, the gang
 * filter in the parameters, and a profile without period or roles.
 */
const serveAll = (routes: Record<string, unknown>) =>
  backend(Object.fromEntries(Object.entries(routes).map(([k, v]) => [k, () => v])));

afterEach(() => {
  vi.unstubAllGlobals();
  window.history.replaceState(null, '', '/');
});

const entry = (key: string, over: object = {}) => ({
  key,
  name: key,
  family: 'Labour Registers',
  section: 'A.4.4',
  domain: 'REPORT',
  producingScreen: 'O-03',
  payrollTypes: null,
  periodicity: 'Monthly',
  lockRequirement: 'POST_LOCK',
  versionNo: 1,
  layoutFormat: null,
  blockedReason: null,
  status: 'BLOCKED',
  ...over,
});

function reports() {
  return serveAll({
    'GET /v1/reports/labour-catalogue': paged([
      entry('LABOUR_WAGE_DETAIL'),
      entry('LABOUR_CPF_REGISTER', { status: 'AVAILABLE' }),
    ]),
    'GET /v1/labour/options': { depots: [{ id: 7, code: 'DP-DL01', name: 'Depot Okhla' }], designations: [] },
    'GET /v1/labour/gangs': paged([{ gangId: 3, gangCode: 'DP-DL01-G002' }]),
  });
}

describe('O-03 coverage gaps', () => {
  it('SRS-18-24: a blocked entry without reason and an available one: View says Available, types read —', async () => {
    reports();
    wrap(<LabourReports />, me(['O-03:VIEW']));
    const table = await screen.findByRole('table', { name: t('o03.catalogue') });
    const blocked = (await within(table).findByText('LABOUR_WAGE_DETAIL')).closest('tr') as HTMLElement;
    expect(within(blocked).getByText('—')).toBeInTheDocument();
    const pill = within(blocked).getByText(t('o03.status.blocked'));
    expect(pill).toHaveClass('p-warn');
    expect(pill).not.toHaveAttribute('title');
    const available = within(table).getByText('LABOUR_CPF_REGISTER').closest('tr') as HTMLElement;
    expect(within(available).getByText(t('o03.status.available'))).toHaveClass('p-ok');
    fireEvent.click(within(blocked).getByRole('button', { name: t('o03.viewName', ['LABOUR_WAGE_DETAIL']) }));
    expect((await screen.findAllByText(t('o03.status.available'))).length).toBeGreaterThan(1);
    const annexure = screen.getByRole('table', { name: t('o03.annexure') });
    expect(within(annexure).getAllByText('—').length).toBeGreaterThanOrEqual(2);
  });

  it('SRS-G-54: the gang chosen fills the parameters', async () => {
    reports();
    wrap(<LabourReports />, me(['O-03:VIEW']));
    const gang = await screen.findByRole('combobox', { name: t('o03.filter.gang') });
    await within(gang).findByRole('option', { name: 'DP-DL01-G002' });
    fireEvent.change(gang, { target: { value: 'DP-DL01-G002' } });
    expect(await screen.findByText(`${t('o03.allDepots')} · DP-DL01-G002`)).toBeInTheDocument();
  });

  it('SRS-G-54: a profile without period or roles reads — for the period and has Generate off', async () => {
    reports();
    wrap(<LabourReports />, me(['O-03:VIEW'], { period: undefined, roles: undefined } as never));
    const generate = await screen.findByRole('button', { name: t('o03.act.generate') });
    expect(generate).toBeDisabled();
    expect(generate).toHaveAttribute('data-why', t('o03.why.maker'));
    const periodRow = screen.getByText(t('o03.p.period')).closest('div')?.parentElement as HTMLElement;
    await waitFor(() => expect(periodRow.textContent).toContain('—'));
    const table = screen.getByRole('table', { name: t('o03.catalogue') });
    await within(table).findByText('LABOUR_WAGE_DETAIL');
    expect(within(table).queryByRole('button', { name: '✔' })).not.toBeInTheDocument();
  });
});
