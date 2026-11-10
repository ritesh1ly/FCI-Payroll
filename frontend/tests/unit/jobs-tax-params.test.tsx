import { fireEvent, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import type { Halt, JobDef } from '@/modules/jobs/api/types';
import { BatchJobs } from '@/modules/jobs/components/BatchJobs';
import { requestedJob } from '@/modules/jobs/components/SubmitJobForm';
import { backend, paged, referenceLists, t, wrap } from './support/harness';

/**
 * C-13 parameters of the Module 08 background jobs (SRS-NF-7): a financial year and a quarter,
 * checked as the server's JobCatalog checks them; the C-10 / C-11 screens open the form with the job
 * and its year filled in, never the unit.
 */

const partB: JobDef = {
  type: 'TAX_FORM16_PART_B',
  params: [
    { name: 'locationId', kind: 'LOCATION', required: true },
    { name: 'finYear', kind: 'FIN_YEAR', required: true },
  ],
};
const quarterly: JobDef = {
  type: 'TAX_24Q_RETURN',
  params: [...partB.params, { name: 'quarter', kind: 'QUARTER', required: true }],
};

describe('financial year and quarter parameters', () => {
  it('SRS_NF_7_aScreenAsksForTheJobAndItsYearButNeverTheUnit', () => {
    const asked = requestedJob('?s=C-13&job=TAX_24Q_RETURN&finYear=2025-26&quarter=Q2&locationId=9', [
      partB,
      quarterly,
    ]);
    expect(asked?.def.type).toBe('TAX_24Q_RETURN');
    expect(asked?.values).toEqual({ finYear: '2025-26', quarter: 'Q2' });
    expect(requestedJob('?s=C-13&job=NOPE', [partB])).toBeNull();
    expect(requestedJob('', [partB])).toBeNull();
    // An over-long value is cut to the field's size; review checks it like a typed value.
    expect(
      requestedJob(`?job=TAX_FORM16_PART_B&finYear=${'9'.repeat(40)}`, [partB])?.values.finYear,
    ).toHaveLength(20);
  });
});

describe('C-10 / C-11 hand-off to C-13', () => {
  afterEach(() => window.history.replaceState({}, '', '/'));

  const halt: Halt = {
    engaged: false,
    haltId: null,
    haltedBy: null,
    haltedAt: null,
    reason: null,
    releaseApprovalId: null,
    releaseRequestedBy: null,
    releaseReason: null,
  };

  it('SRS_NF_7_theAskedJobOpensTheSubmitFormWithItsYearAndQuarter', async () => {
    window.history.replaceState({}, '', '/payroll?s=C-13&job=TAX_24Q_RETURN&finYear=2025-26&quarter=Q2');
    backend({
      'GET /v1/jobs/halt': () => halt,
      'GET /v1/jobs?': (url) => paged([], 0, url),
      'GET /v1/jobs/types': () => ({
        types: [{ type: 'PAYROLL_DRY_RUN', params: [] }, partB, quarterly],
        currentPeriod: '2026-10',
        locations: [{ id: 3, code: 'DH001', label: 'DO - Delhi HQ (DH001)' }],
      }),
      'GET /v1/reference/lists': () =>
        referenceLists({ JOB_TYPE: [['TAX_24Q_RETURN', 'Form 24Q return generation']] }),
    });
    wrap(<BatchJobs />, ['C-13:VIEW', 'C-13:CREATE']);
    expect(await screen.findByRole('tab', { name: t('c13.tab.submit') })).toHaveClass('pri');
    expect(await screen.findByDisplayValue('2025-26')).toBeInTheDocument();
    expect((document.querySelector('#job-type') as HTMLSelectElement).value).toBe('TAX_24Q_RETURN');
    expect((document.querySelector('#job-quarter') as HTMLSelectElement).value).toBe('Q2');
    expect((document.querySelector('#job-locationId') as HTMLSelectElement).value).toBe('3');
    // The quarter can be changed before the job is sent.
    fireEvent.change(document.querySelector('#job-quarter') as HTMLSelectElement, {
      target: { value: 'Q3' },
    });
    expect((document.querySelector('#job-quarter') as HTMLSelectElement).value).toBe('Q3');
  });
});
