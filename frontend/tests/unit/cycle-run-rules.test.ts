import { describe, expect, it } from 'vitest';
import { runningRunNo } from '@/modules/jobs/business/cycleRules';

/** SRS-15-28 / SRS l.1734 (CYC-409-RUNNING): Verify waits for an ACTUAL computation still running. */
describe('cycle running run', () => {
  const run = (type: string, status: string, runNo: number) => ({ type, status, runNo });

  it('SRS-15-28: the run number of the running ACTUAL computation', () => {
    expect(
      runningRunNo([
        run('SIMULATION', 'RUNNING', 1),
        run('ACTUAL', 'COMPLETED', 2),
        run('ACTUAL', 'RUNNING', 3),
      ]),
    ).toBe(3);
  });

  it('SRS-15-28: none when no ACTUAL run is running; a simulation or a finished run never holds Verify', () => {
    expect(runningRunNo([])).toBeNull();
    expect(runningRunNo([run('SIMULATION', 'RUNNING', 1)])).toBeNull();
    expect(runningRunNo([run('ACTUAL', 'COMPLETED', 2), run('ACTUAL', 'FAILED', 4)])).toBeNull();
  });

  it('SRS-15-28: a running run numbered 0 is still a run', () => {
    expect(runningRunNo([run('ACTUAL', 'RUNNING', 0)])).toBe(0);
  });
});
