import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { EmployeeMaster } from '@/modules/employee';
import { backend, paged, problem, referenceLists, t, wrap } from './support/harness';

/**
 * M-01 Employee Master (SRS-1-7, SRS-1-11, SRS-1-70, SRS-G-30, SRS-G-45): the Edit Record of the first row (and its
 * refusal on an empty list), the Approve on a row sent to the inbox, the FND filter, the record's HRMS values absent or
 * present (a dash, never an empty cell), the migrated record's legacy number and the order card of the record.
 */

afterEach(() => vi.unstubAllGlobals());

const employee = {
  eis: '104882',
  name: 'Rakesh Sharma',
  designation: 'Manager',
  band: 'E-4',
  location: 'DO — Delhi HQ (DH001)',
  payPattern: 'IDA',
  employeeType: 'STAFF',
  status: 'ACTIVE',
  payScale: 'E-4',
  basic: 62400,
  city: 'X',
  cpfCode: 'CPF/104882',
  bank: 'SBI',
};

const detail = {
  eis: '104882',
  firstName: 'Rakesh',
  lastName: null,
  panMasked: 'XXXXXX234K',
  currentBasic: 62400,
  status: 'ACTIVE',
  employmentStatus: 'IN_SERVICE',
  employeeType: 'STAFF',
};

interface Opts {
  rows?: object[];
  extras?: object;
  detail?: object;
  documents?: () => unknown;
  types?: () => unknown;
}

function routes(o: Opts = {}) {
  return backend({
    'GET /v1/employees/offices': () => [{ code: 'DH001', label: 'DO — Delhi HQ (DH001)' }],
    'GET /v1/employees/counts': () => ({ total: 1, active: 1 }),
    'GET /v1/employees/104882/hrms-record': () => o.extras ?? { hrmsAvailable: false },
    'GET /v1/employees/104882/member-ids': (url) =>
      paged(
        [
          {
            idType: 'CPF',
            memberId: null,
            category: null,
            zone: null,
            effectiveFrom: '2011-04-01',
            effectiveTo: '2020-03-31',
            status: 'CLOSED',
            reason: 'Moved to the new zone code',
          },
        ],
        1,
        url,
      ),
    'GET /v1/employees/104882/staff-numbers': (url) =>
      paged(
        [
          {
            oldNumber: '088123',
            newNumber: '099882',
            origin: null,
            destination: null,
            effectiveDate: null,
            event: 'ORIGINAL',
          },
        ],
        1,
        url,
      ),
    'GET /v1/employees/104882/control-flags': () => ({ eis: '104882', joiningDate: '2010-06-01', flags: [] }),
    'GET /v1/employees/104882/allowance-flags': () => ({ eis: '104882', flags: [] }),
    'GET /v1/employees/104882/lifecycle-events': (url) => paged([], 0, url),
    'GET /v1/employees/104882/transfers': (url) => paged([], 0, url),
    'GET /v1/employees/104882/documents': o.documents ?? (() => []),
    'POST /v1/employees/104882/documents': () => ({ status: 201, body: { id: 5 } }),
    'GET /v1/employees/104882': () => ({ ...detail, ...(o.detail ?? {}) }),
    'GET /v1/employees?': (url) => paged(o.rows ?? [employee], (o.rows ?? [employee]).length, url),
    'GET /v1/document-types':
      o.types ?? (() => [{ code: 'OTHER', name: 'Other document', referenceRequired: true }]),
    'GET /v1/reference/lists': () =>
      referenceLists({
        EMPLOYEE_RECORD_STATUS: [
          ['ACTIVE', 'Active'],
          ['PAYROLL_SETUP_PENDING', 'Payroll setup pending'],
        ],
        ORDER_COPY_DOCUMENT_TYPE: [['OTHER', 'Other document']],
      }),
  });
}

describe('M-01 Edit Record, the row Approve and the FND filter', () => {
  it('SRS-G-45: Edit Record opens the first record for edit; Escape closes it', async () => {
    routes();
    wrap(<EmployeeMaster />, ['M-01:VIEW', 'M-01:FLAG']);
    await screen.findByText('Rakesh Sharma');
    await userEvent.click(screen.getByRole('button', { name: t('m01.w.editRecord') }));
    const dialog = await screen.findByRole('dialog', { name: t('wf.viewHead', [t('screen.M-01')]) });
    fireEvent.keyDown(dialog, { key: 'Enter' });
    expect(screen.getByRole('dialog', { name: t('wf.viewHead', [t('screen.M-01')]) })).toBeTruthy();
    fireEvent.keyDown(dialog, { key: 'Escape' });
    await waitFor(() =>
      expect(screen.queryByRole('dialog', { name: t('wf.viewHead', [t('screen.M-01')]) })).toBeNull(),
    );
  });

  it('SRS-G-45: with no record listed Edit Record says there is none and opens nothing', async () => {
    routes({ rows: [] });
    wrap(<EmployeeMaster />, ['M-01:VIEW', 'M-01:FLAG']);
    expect((await screen.findAllByText(t('m01.none'))).length).toBeGreaterThan(0);
    await userEvent.click(screen.getByRole('button', { name: t('m01.w.editRecord') }));
    await waitFor(() => expect(screen.getAllByText(t('m01.none')).length).toBeGreaterThan(1));
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('R-MC: the Checker approving a row is sent to the Approvals Inbox; nothing is posted', async () => {
    const calls = routes({
      rows: [{ ...employee, basic: null, payPattern: null, payScale: null, city: null }],
    });
    wrap(<EmployeeMaster />, ['M-01:VIEW', 'M-01:APPROVE']);
    await screen.findByText('Rakesh Sharma');
    const row = screen.getByText('Rakesh Sharma').closest('tr') as HTMLElement;
    // A Basic, pattern, scale and city not in the record show a dash.
    expect(within(row).getAllByText('—').length).toBeGreaterThanOrEqual(5);
    await userEvent.click(within(row).getByRole('button', { name: '✔' }));
    expect(await screen.findByText(t('m01.w.approveInInbox'))).toBeTruthy();
    expect(calls.some((c) => c.method !== 'GET')).toBe(false);
  });

  it('SRS-1-11: the FND-only filter asks the server for FND; the setup-pending status is offered as drawn', async () => {
    const calls = routes();
    wrap(<EmployeeMaster />, ['M-01:VIEW']);
    await screen.findByText('Rakesh Sharma');
    expect(await screen.findByRole('option', { name: t('m01.w.setupOption') })).toBeTruthy();
    expect(screen.getByRole('option', { name: 'Active' })).toBeTruthy();
    await userEvent.click(screen.getByText(t('m01.w.moreFilters')));
    await userEvent.click(screen.getByRole('checkbox', { name: t('m01.w.fndOnly') }));
    await waitFor(() =>
      expect(calls.some((c) => c.url.includes('/v1/employees?') && c.url.includes('payPattern=FND'))).toBe(
        true,
      ),
    );
  });
});

describe('M-01 the record detail as HRMS holds it', () => {
  it('SRS-1-70: a migrated record shows its legacy number, HRA rate and casual-labour flag; histories show dashes', async () => {
    routes({
      extras: { hrmsAvailable: false, recordOrigin: 'MIGRATED', legacyEmpNum: 'L-77821' },
      detail: { hraRateApplied: 24, casualLabour: true },
    });
    wrap(<EmployeeMaster />, ['M-01:VIEW']);
    expect(await screen.findByText(t('m01.w.d.legacy', ['L-77821']))).toBeTruthy();
    expect(screen.getByText('24%')).toBeTruthy();
    expect(screen.getByText(t('m01.w.mid.status.CLOSED'))).toBeTruthy();
    expect(screen.getByText('Moved to the new zone code')).toBeTruthy();
    const staff = screen.getByText('088123').closest('tr') as HTMLElement;
    expect(within(staff).getAllByText('—')).toHaveLength(3);
    // The title of the member-ID table carries the name with no surname.
    expect(screen.getByText(t('m01.w.memberIds', ['104882', 'Rakesh']))).toBeTruthy();
  });

  it('SRS-1-70: a migrated record without its legacy number is shown as from the interface', async () => {
    routes({ extras: { hrmsAvailable: false, recordOrigin: 'MIGRATED', legacyEmpNum: null } });
    wrap(<EmployeeMaster />, ['M-01:VIEW']);
    expect(await screen.findByText(t('m01.w.d.interface'))).toBeTruthy();
  });
});

describe('M-01 the order card of the record', () => {
  it('SRS-G-30: a stored order opens from View order; Establishment attaches a new one with its particulars', async () => {
    const calls = routes({
      documents: () => [
        {
          id: 3,
          documentTypeName: 'Other document',
          referenceNumber: 'EST/77',
          orderDate: '2026-10-20',
          fileName: 'order.pdf',
          uploadedBy: 'maker.delhi',
          uploadedAt: '2026-10-21T05:00:00Z',
        },
      ],
    });
    wrap(<EmployeeMaster />, ['M-01:VIEW', 'M-01:FLAG']);
    await screen.findByText('Rakesh Sharma');
    const card = document.querySelector('[data-order-card]') as HTMLElement;
    const view = await within(card).findByRole('link', { name: t('wf.ord.viewOrder') });
    expect(view.getAttribute('href')).toMatch(/\/v1\/employees\/104882\/documents\/3$/);
    await within(card).findByRole('option', { name: 'Other document' });
    const file = card.querySelector('input[type=file]') as HTMLInputElement;
    await waitFor(() => expect(file.disabled).toBe(false));
    fireEvent.change(card.querySelector('input[type=date]') as HTMLInputElement, {
      target: { value: '2026-10-20' },
    });
    // The type needs its reference: without it nothing is sent.
    fireEvent.change(file, {
      target: { files: [new File(['%PDF'], 'order.pdf', { type: 'application/pdf' })] },
    });
    expect(await within(card).findByText(t('wf.ord.particularsFirst'))).toBeTruthy();
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(0);
    fireEvent.change(within(card).getByPlaceholderText(t('wf.ord.referencePlaceholder')), {
      target: { value: 'EST/78' },
    });
    fireEvent.change(within(card).getByPlaceholderText(t('wf.ord.officePlaceholder')), {
      target: { value: 'GM (P), Delhi' },
    });
    fireEvent.change(file, {
      target: { files: [new File(['%PDF'], 'order.pdf', { type: 'application/pdf' })] },
    });
    expect(await within(card).findByText(t('wf.ord.stored', [1]))).toBeTruthy();
    const post = calls.find((c) => c.method === 'POST')?.body as FormData;
    expect(post.get('documentType')).toBe('OTHER');
    expect(post.get('reference')).toBe('EST/78');
    expect(post.get('orderDate')).toBe('2026-10-20');
    expect(post.get('issuingAuthority')).toBe('GM (P), Delhi');
    expect((post.get('file') as File).name).toBe('order.pdf');
  });

  it('SRS-G-30: a type with no reference rule needs neither reference nor issuing office; neither is sent', async () => {
    const calls = routes({ types: () => [] });
    wrap(<EmployeeMaster />, ['M-01:VIEW', 'M-01:FLAG']);
    await screen.findByText('Rakesh Sharma');
    const card = document.querySelector('[data-order-card]') as HTMLElement;
    await within(card).findByRole('option', { name: 'Other document' });
    const file = card.querySelector('input[type=file]') as HTMLInputElement;
    await waitFor(() => expect(file.disabled).toBe(false));
    fireEvent.change(card.querySelector('input[type=date]') as HTMLInputElement, {
      target: { value: '2026-10-20' },
    });
    fireEvent.change(file, {
      target: { files: [new File(['%PDF'], 'order.pdf', { type: 'application/pdf' })] },
    });
    expect(await within(card).findByText(t('wf.ord.stored', [1]))).toBeTruthy();
    const post = calls.find((c) => c.method === 'POST')?.body as FormData;
    expect(post.has('reference')).toBe(false);
    expect(post.has('issuingAuthority')).toBe(false);
  });

  it('R-RBAC: a viewer cannot attach; a failing type list (500) is shown on the card', async () => {
    routes({ types: () => problem(500, 'SYS-500') });
    wrap(<EmployeeMaster />, ['M-01:VIEW']);
    await screen.findByText('Rakesh Sharma');
    const card = document.querySelector('[data-order-card]') as HTMLElement;
    expect(await within(card).findByRole('alert')).toBeTruthy();
    expect((card.querySelector('input[type=file]') as HTMLInputElement).disabled).toBe(true);
    expect(within(card).getByText(t('wf.ord.viewOnly'))).toBeTruthy();
  });
});
