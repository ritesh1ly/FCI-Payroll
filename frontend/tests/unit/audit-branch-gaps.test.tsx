import { screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AuditTrail } from '@/modules/audit';
import { backend, problem, t, wrap } from './support/harness';

/** Branch gaps of A-05 / X-04: the record types while they cannot be read (SRS-20-29). */

afterEach(() => vi.unstubAllGlobals());

describe('X-04 Record enquiry branch gaps', () => {
  it('SRS-20-29: while the record types cannot be read only the prompt is offered', async () => {
    backend({
      'GET /v1/audit/record-types': () => problem(503, 'COM-503-UNAVAILABLE'),
      'GET /v1/admin/audit': () => ({ items: [], page: 0, size: 50, total: 0 }),
    });
    wrap(<AuditTrail />, ['A-05:VIEW']);
    const type = await screen.findByLabelText(t('x04.type'));
    expect(type.querySelectorAll('option')).toHaveLength(1);
    expect(type.querySelector('option')?.textContent).toBe(t('x04.pickType'));
  });
});
