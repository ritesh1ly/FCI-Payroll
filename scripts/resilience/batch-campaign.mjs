#!/usr/bin/env node
/**
 * Batch module campaign (owner 2026-10-30: "chaos monkey test, deep profiling and load test of the batch
 * module; all the edge cases, positive, negative and resiliency scenarios"; plan and exit criteria in
 * docs/engineering/RESILIENCE_TESTING.md §Batch campaign). Drives C-13 (/v1/jobs) of a local stack as
 * the users would, and checks every outcome against the database (one result per member, nothing left
 * PICKED or RUNNING, no duplicate run):
 *
 *   P  positive: a large-office dry run completes with one result per member (throughput measured);
 *      two offices at once; the priority rule (bulk work never takes the last slot, DR-17)
 *   N  negative: duplicate, future period, unknown or foreign unit, priority out of range, the
 *      maker deciding their own job, a command the state does not allow, a restart of a finished job
 *   R  resilience: pause / resume, stop / restart, the batch node killed mid-run then restarted, Chaos
 *      Monkey exceptions and latency on the batch node, the kill switch (halt) while jobs are queued
 *
 * Needs the volume + chaos stack: WAR built with -Pfci.chaos=true, deployed with
 *   deploy-local.ps1 -PortOffset 300 -Runtime scripts\local\runtime-b -Volume -Chaos -OtpRequired Y
 * Run: node scripts/resilience/batch-campaign.mjs [--only P1,R3] [--period YYYY-MM]
 * Writes docs/perf/batch-campaign-<date>.json; exit code 1 when a check fails.
 */
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { sleep, stack } from '../support/fci-client.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const args = Object.fromEntries(
  process.argv.slice(2).flatMap((a, i, all) => (a.startsWith('--') ? [[a.slice(2), all[i + 1] ?? 'true']] : [])),
);
const only = args.only ? new Set(args.only.split(',')) : null;
const fci = stack({ root: ROOT });
const { signIn, decide, sqlRows, one, monkey, health, kill, restartBackends } = fci;

const checks = [];
const report = { started: new Date().toISOString(), scenarios: {} };
function check(scenario, what, ok, detail = '') {
  checks.push({ scenario, what, ok: Boolean(ok), detail: String(detail) });
  console.log(`  ${ok ? 'PASS' : 'FAIL'} ${scenario}: ${what}${detail ? ` (${detail})` : ''}`);
}
const wanted = (id) => !only || only.has(id);

/** The office desks of the volume set (docs/perf/VOLUME_DATA.md). */
async function desk(region) {
  const code = region.toLowerCase();
  const maker = await signIn(`vol.maker.${code}@fci-demo.test`, `RO-${region}`, 'PAYROLL_MAKER');
  const checker = await signIn(`vol.checker.${code}@fci-demo.test`, `RO-${region}`, 'PAYROLL_CHECKER');
  const types = maker.json(await maker.call('GET', '/v1/jobs/types'));
  const unit = types.locations?.find((l) => l.code === `RO-${region}`) ?? types.locations?.[0];
  const d = { region, maker, checker, unit: unit?.id, period: types.currentPeriod };
  // Long campaigns outlive the idle timeout: sign the desk in again when a call answers 401.
  d.refresh = async () => {
    d.maker = await signIn(`vol.maker.${code}@fci-demo.test`, `RO-${region}`, 'PAYROLL_MAKER');
    d.checker = await signIn(`vol.checker.${code}@fci-demo.test`, `RO-${region}`, 'PAYROLL_CHECKER');
  };
  return d;
}

const job = async (s, id) => s.json(await s.call('GET', `/v1/jobs/${id}`)).job ?? {};

/** Submits a job; returns {status, id, body}. */
async function submit(s, jobType, params, reason, priority) {
  const r = await s.call('POST', '/v1/jobs', { jobType, params, reason, ...(priority !== undefined ? { priority } : {}) });
  const body = s.json(r);
  return { status: r.status, id: body.id, body };
}

/** A dry run of the desk's office, approved by its checker; returns the job id. */
async function approvedDryRun(d, period, reason) {
  let r = await submit(d.maker, 'PAYROLL_DRY_RUN', { locationId: String(d.unit), payPeriod: period }, reason);
  if (r.status === 401) {
    await d.refresh();
    r = await submit(d.maker, 'PAYROLL_DRY_RUN', { locationId: String(d.unit), payPeriod: period }, reason);
  }
  if (r.status >= 300) throw new Error(`${d.region} ${period}: submit ${r.status} ${JSON.stringify(r.body)}`);
  const decided = await decide(d.checker, (await job(d.maker, r.id)).approvalId);
  if (decided >= 300) throw new Error(`${d.region}: decision ${decided}`);
  return r.id;
}

async function waitFor(s, id, states, seconds = 900) {
  let j = await job(s, id);
  const until = Date.now() + seconds * 1000;
  while (!states.includes(j.status) && Date.now() < until) {
    await sleep(500);
    j = await job(s, id);
  }
  return j;
}

/** After a RESUME / RESTART: the new attempt (the node applies it at its next poll), then its end. */
async function waitForAttempt(s, id, previousAttempt, seconds = 900) {
  const until = Date.now() + seconds * 1000;
  let j = await job(s, id);
  while (j.attemptNo <= previousAttempt && Date.now() < until) {
    await sleep(500);
    j = await job(s, id);
  }
  return waitFor(s, id, ['COMPLETED', 'FAILED', 'STOPPED', 'PAUSED'], Math.max(1, (until - Date.now()) / 1000));
}

/** The simulation run of a job and its results: rows, members, duplicates. */
function results(jobId) {
  const run = one(`SELECT MAX(run_id) FROM computation_run WHERE parameters_json LIKE '{"jobRequestId":${jobId},%'`);
  if (!run || run === 'null') return { run: null, rows: 0, members: 0 };
  const [rows, members] = sqlRows(
    `SELECT COUNT(*), COUNT(DISTINCT employee_id) FROM payroll_result WHERE run_id = ${run}`,
  )[0].map(Number);
  return { run: Number(run), rows, members };
}

/** Jobs of the stack that a crash or a fault could leave half-way. */
const stuck = () =>
  Number(one("SELECT COUNT(*) FROM batch_job_request WHERE job_status = 'PICKED' AND picked_at < CURRENT_TIMESTAMP - INTERVAL '5' MINUTE"));

/**
 * The job's counts are those its executions saved: after a node loss the killed attempt's counts
 * died with it, so {@code committedBefore} (members committed before the kill) are added back.
 */
function oneResultPerMember(scenario, j, res, committedBefore = 0) {
  check(scenario, 'COMPLETED', j.status === 'COMPLETED', `${j.status} ${j.errorMessage ?? ''}`.trim());
  check(scenario, 'one result per member (no duplicate)', res.rows > 0 && res.rows === res.members, `${res.rows} rows, ${res.members} members`);
  check(
    scenario,
    'every member read is written or skipped',
    j.readCount + committedBefore >= res.members + j.skipCount && j.readCount <= res.members + j.skipCount,
    `read ${j.readCount}${committedBefore ? ` + ${committedBefore} before the kill` : ''}, written ${res.members}, skipped ${j.skipCount}`,
  );
}

/** Periods for dry runs: one per scenario, so the duplicate guard never meets an earlier scenario's job. */
function periods(current, n) {
  const [y, m] = current.split('-').map(Number);
  return Array.from({ length: n }, (_, i) => {
    const d = new Date(Date.UTC(y, m - 1 - i, 1));
    return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, '0')}`;
  });
}

async function main() {
  console.log(`batch campaign on ${fci.RUNTIME}; online ${await health('online')}, batch ${await health('batch')}`);
  const pb = await desk('PB');
  // Offices whose state has Professional Tax configured (pt_state_master): PB, WB, MH, TN; the other
  // volume regions refuse every member as "not configured" (PENDING VOL-PT).
  const wb = await desk('WB');
  const mh = await desk('MH');
  const tn = await desk('TN');
  // Every scenario uses the current period: the volume set locks the earlier months (a dry run of a
  // locked cycle is refused, CYCLE_LOCKED). No two open jobs share an office (the duplicate guard).
  const current = args.period ?? pb.period;
  const p = Array.from({ length: 6 }, () => current);
  const stamp = Date.now();
  report.offices = { PB: pb.unit, HR: wb.unit, UP: mh.unit, MP: tn.unit };

  if (wanted('P1')) {
    console.log('P1 large office: Punjab dry run (about 2,049 staff)');
    const id = await approvedDryRun(pb, p[0], `Campaign P1 ${stamp}`);
    const j = await waitFor(pb.maker, id, ['COMPLETED', 'FAILED']);
    const res = results(id);
    oneResultPerMember('P1', j, res);
    const rate = j.durationSeconds ? (res.members / j.durationSeconds).toFixed(1) : 'n/a';
    console.log(`  ${res.members} members in ${j.durationSeconds} s: ${rate} members/s`);
    report.scenarios.P1 = { id, durationSeconds: j.durationSeconds, members: res.members, membersPerSecond: Number(rate) };
  }

  if (wanted('P2')) {
    console.log('P2 two offices at once (West Bengal, Maharashtra)');
    const ids = [await approvedDryRun(wb, p[0], `Campaign P2 WB ${stamp}`), await approvedDryRun(mh, p[0], `Campaign P2 MH ${stamp}`)];
    let both = 0;
    for (let i = 0; i < 300; i++) {
      const s = await Promise.all([job(wb.maker, ids[0]), job(mh.maker, ids[1])]);
      if (s.every((x) => x.status === 'RUNNING')) both++;
      if (s.every((x) => ['COMPLETED', 'FAILED'].includes(x.status))) break;
      await sleep(1000);
    }
    const [a, b] = [await job(wb.maker, ids[0]), await job(mh.maker, ids[1])];
    oneResultPerMember('P2 WB', a, results(ids[0]));
    oneResultPerMember('P2 MH', b, results(ids[1]));
    console.log(`  ran side by side for ${both} s; ${a.durationSeconds} s and ${b.durationSeconds} s`);
    report.scenarios.P2 = { ids, sideBySideSeconds: both, durations: [a.durationSeconds, b.durationSeconds] };
  }

  if (wanted('P3')) {
    console.log('P3 priority: three dry runs (low priority) never hold both slots of the node (DR-17)');
    const ids = [
      await approvedDryRun(wb, p[1], `Campaign P3 WB ${stamp}`),
      await approvedDryRun(mh, p[1], `Campaign P3 MH ${stamp}`),
      await approvedDryRun(tn, p[1], `Campaign P3 TN ${stamp}`),
    ];
    let most = 0;
    for (let i = 0; i < 900; i++) {
      const running = Number(one(`SELECT COUNT(*) FROM batch_job_request WHERE job_request_id IN (${ids}) AND job_status IN ('PICKED', 'RUNNING')`));
      most = Math.max(most, running);
      const done = Number(one(`SELECT COUNT(*) FROM batch_job_request WHERE job_request_id IN (${ids}) AND job_status IN ('COMPLETED', 'FAILED')`));
      if (done === ids.length) break;
      await sleep(1000);
    }
    check('P3', 'at most one low-priority job at a time on a two-slot node', most === 1, `peak ${most}`);
    const finals = await Promise.all([job(wb.maker, ids[0]), job(mh.maker, ids[1]), job(tn.maker, ids[2])]);
    check('P3', 'all three complete in turn', finals.every((j) => j.status === 'COMPLETED'), finals.map((j) => j.status).join(' '));
    check('P3', 'queued at the type priority (2)', finals.every((j) => j.priority === 2), finals.map((j) => j.priority).join(' '));
    report.scenarios.P3 = { ids, peakConcurrent: most };
  }

  if (wanted('N')) {
    console.log('N negative cases');
    const first = await submit(pb.maker, 'PAYROLL_DRY_RUN', { locationId: String(pb.unit), payPeriod: p[2] }, `Campaign N ${stamp}`);
    check('N1', 'a job is accepted for the checker (202)', first.status === 202, first.status);
    const again = await submit(pb.maker, 'PAYROLL_DRY_RUN', { locationId: String(pb.unit), payPeriod: p[2] }, `Campaign N again ${stamp}`);
    check('N1', 'the same job again is refused while the first is open (409 JOB-409-QUEUED)', again.status === 409 && again.body.errorCode === 'JOB-409-QUEUED', `${again.status} ${again.body.errorCode}`);
    const future = periods(pb.period, 1)[0].replace(/^(\d{4})-(\d{2})$/, (_, y, m) => (m === '12' ? `${+y + 1}-01` : `${y}-${String(+m + 1).padStart(2, '0')}`));
    const fut = await submit(pb.maker, 'PAYROLL_DRY_RUN', { locationId: String(pb.unit), payPeriod: future }, 'Campaign N future');
    check('N2', 'a period after the current one is refused (422)', fut.status === 422, `${fut.status} ${fut.body.errorCode}`);
    const bad = await submit(pb.maker, 'PAYROLL_DRY_RUN', { locationId: '999999999', payPeriod: p[2] }, 'Campaign N unknown unit');
    check('N3', 'an unknown unit is refused (4xx)', bad.status >= 400 && bad.status < 500, bad.status);
    const foreign = await submit(pb.maker, 'PAYROLL_DRY_RUN', { locationId: String(wb.unit), payPeriod: p[2] }, 'Campaign N other office');
    check('N3', "another office's unit is refused (403 / 422)", [403, 422].includes(foreign.status), `${foreign.status} ${foreign.body.errorCode}`);
    for (const priority of [0, 10]) {
      const r = await submit(pb.maker, 'PAYROLL_DRY_RUN', { locationId: String(pb.unit), payPeriod: p[3] }, 'Campaign N priority', priority);
      check('N4', `priority ${priority} is refused (400 / 422)`, [400, 422].includes(r.status), r.status);
    }
    const missing = await pb.maker.call('POST', '/v1/jobs', { jobType: 'PAYROLL_DRY_RUN', params: { locationId: String(pb.unit), payPeriod: p[3] } });
    check('N5', 'a job without a reason is refused (400 / 422)', [400, 422].includes(missing.status), missing.status);
    const unknownType = await submit(pb.maker, 'NO_SUCH_JOB', {}, 'Campaign N type');
    check('N5', 'an unknown job type is refused (4xx)', unknownType.status >= 400 && unknownType.status < 500, unknownType.status);
    const own = await decide(pb.maker, (await job(pb.maker, first.id)).approvalId);
    check('N6', 'the maker cannot decide their own job (403)', own === 403, own);
    const reject = await decide(pb.checker, (await job(pb.maker, first.id)).approvalId, 'REJECT', 'Campaign: refused on purpose');
    const rejected = await job(pb.maker, first.id);
    check('N6', "the checker's rejection ends the job without a run", reject < 300 && rejected.status === 'REJECTED' && results(first.id).run === null, `${reject} ${rejected.status}`);
    const done = Number(one("SELECT MAX(job_request_id) FROM batch_job_request WHERE job_status = 'COMPLETED' AND job_name = 'PAYROLL_DRY_RUN' AND location_id = " + pb.unit));
    if (done) {
      for (const cmd of ['pause', 'resume', 'stop', 'restart']) {
        const r = await pb.maker.call('POST', `/v1/jobs/${done}/${cmd}`, { reason: 'Campaign N invalid state' });
        check('N7', `${cmd.toUpperCase()} of a completed job is refused (409 JOB-409-STATE)`, r.status === 409 && pb.maker.json(r).errorCode === 'JOB-409-STATE', `${r.status} ${pb.maker.json(r).errorCode}`);
      }
    }
    const noReason = await pb.maker.call('POST', `/v1/jobs/${done || first.id}/stop`, { reason: '' });
    check('N7', 'a command without a reason is refused (400 / 422)', [400, 422].includes(noReason.status), noReason.status);
    const otherOffice = await wb.maker.call('GET', `/v1/jobs/${first.id}`);
    check('N8', "another office cannot see the job (403 / 404)", [403, 404].includes(otherOffice.status), otherOffice.status);
  }

  // A volume office runs in seconds; R1-R3 slow the batch node (latency on every watched call) so a
  // command or the kill lands mid-run. A command that arrives after the job ended is REJECTED by the
  // node ("not possible while the job is COMPLETED"), which N7 and the control table show.
  // Slowed only until the command lands (latency on every call makes a member take seconds).
  const SLOW = { level: 1, latencyActive: true, latencyRangeStart: 300, latencyRangeEnd: 600, exceptionsActive: false };

  if (wanted('R1')) {
    console.log('R1 pause and resume a running dry run');
    await monkey('batch', SLOW);
    const id = await approvedDryRun(pb, p[3], `Campaign R1 ${stamp}`);
    let j = await waitFor(pb.maker, id, ['RUNNING', 'COMPLETED', 'FAILED'], 300);
    await sleep(1000);
    const pause = await pb.maker.call('POST', `/v1/jobs/${id}/pause`, { reason: 'Campaign R1 pause' });
    check('R1', 'PAUSE is taken (202)', pause.status === 202, pause.status);
    j = await waitFor(pb.maker, id, ['PAUSED', 'COMPLETED', 'FAILED'], 300);
    await monkey('batch', null);
    check('R1', 'the job reaches PAUSED at a chunk boundary', j.status === 'PAUSED', j.status);
    const pausedAt = results(id).members;
    await sleep(5000);
    check('R1', 'nothing is written while paused', results(id).members === pausedAt, `${pausedAt} -> ${results(id).members}`);
    const resume = await pb.maker.call('POST', `/v1/jobs/${id}/resume`, { reason: 'Campaign R1 resume' });
    check('R1', 'RESUME is taken (202)', resume.status === 202, resume.status);
    j = await waitForAttempt(pb.maker, id, j.attemptNo);
    oneResultPerMember('R1', j, results(id));
    check('R1', 'the resume is a second attempt of the same job', j.attemptNo >= 2, `attempt ${j.attemptNo}`);
  }

  if (wanted('R2')) {
    console.log('R2 stop and restart a running dry run');
    await monkey('batch', SLOW);
    const id = await approvedDryRun(wb, p[3], `Campaign R2 ${stamp}`);
    await waitFor(wb.maker, id, ['RUNNING', 'COMPLETED', 'FAILED'], 300);
    await sleep(1000);
    const stop = await wb.maker.call('POST', `/v1/jobs/${id}/stop`, { reason: 'Campaign R2 stop' });
    check('R2', 'STOP is taken (202)', stop.status === 202, stop.status);
    let j = await waitFor(wb.maker, id, ['STOPPED', 'COMPLETED', 'FAILED'], 300);
    await monkey('batch', null);
    check('R2', 'the job reaches STOPPED', j.status === 'STOPPED', j.status);
    const restart = await wb.maker.call('POST', `/v1/jobs/${id}/restart`, { reason: 'Campaign R2 restart' });
    check('R2', 'RESTART of a stopped job is taken (202)', restart.status === 202, `${restart.status} ${wb.maker.json(restart).errorCode ?? ''}`);
    j = await waitForAttempt(wb.maker, id, j.attemptNo);
    oneResultPerMember('R2', j, results(id));
  }

  if (wanted('R3')) {
    console.log('R3 the batch node is killed mid-run, restarted, and the job restarted');
    await monkey('batch', { ...SLOW, level: 3, latencyRangeStart: 100, latencyRangeEnd: 200 });
    const id = await approvedDryRun(mh, p[3], `Campaign R3 ${stamp}`);
    let j = await waitFor(mh.maker, id, ['RUNNING', 'COMPLETED', 'FAILED'], 300);
    // Killed once some chunks are committed, so the restart must continue after them.
    for (let i = 0; i < 600 && results(id).members < 100 && (await job(mh.maker, id)).status === 'RUNNING'; i++) {
      await sleep(500);
    }
    if ((await job(mh.maker, id)).status !== 'RUNNING') {
      check('R3', 'the job was RUNNING when the node was killed', false, j.status);
    } else {
      const before = results(id).members;
      const pid = kill('batch');
      console.log(`  killed batch pid ${pid} at ${before} members written; restarting the backends`);
      restartBackends(['-Volume', '-Chaos']);
      check('R3', 'online and batch healthy after the restart', (await health('online')) === 'UP' && (await health('batch')) === 'UP');
      const s = await signIn('vol.maker.mh@fci-demo.test', 'RO-MH', 'PAYROLL_MAKER');
      mh.maker = s;
      j = await waitFor(s, id, ['FAILED', 'STOPPED', 'COMPLETED'], 600);
      check('R3', 'crash recovery ends the killed attempt (FAILED, never left RUNNING)', j.status === 'FAILED', j.status);
      const restart = await s.call('POST', `/v1/jobs/${id}/restart`, { reason: 'Campaign R3 restart after node loss' });
      check('R3', 'RESTART after the crash is taken (202)', restart.status === 202, `${restart.status} ${s.json(restart).errorCode ?? ''}`);
      j = await waitForAttempt(s, id, j.attemptNo);
      const res = results(id);
      oneResultPerMember('R3', j, res, before);
      check('R3', 'chunks were committed before the kill', before > 0, `${before} members`);
      check('R3', 'the restart continued from the committed chunks (no member written twice)', res.rows === res.members, `${before} before the kill`);
      report.scenarios.R3 = { id, killedPid: pid, writtenBeforeKill: before, after: res };
    }
  }

  await monkey('batch', null).catch(() => {});

  if (wanted('R4')) {
    console.log('R4 Chaos Monkey exceptions on the batch node (services and repositories)');
    await monkey('batch', { level: 25, latencyActive: false, exceptionsActive: true });
    const id = await approvedDryRun(tn, p[3], `Campaign R4 ${stamp}`);
    let j = await waitFor(tn.maker, id, ['COMPLETED', 'FAILED'], 600);
    await monkey('batch', null);
    console.log(`  under exceptions: ${j.status} ${j.errorMessage ?? ''}`);
    check('R4', 'the job ends (COMPLETED or FAILED), never hangs', ['COMPLETED', 'FAILED'].includes(j.status), j.status);
    check('R4', 'the batch node stays healthy', (await health('batch')) === 'UP');
    if (j.status === 'FAILED') {
      const restart = await tn.maker.call('POST', `/v1/jobs/${id}/restart`, { reason: 'Campaign R4 restart after faults' });
      check('R4', 'RESTART after the faults is taken (202)', restart.status === 202, restart.status);
      j = await waitForAttempt(tn.maker, id, j.attemptNo);
    }
    oneResultPerMember('R4', j, results(id));
    check('R4', 'no job left PICKED by the faults', stuck() === 0, stuck());
  }

  if (wanted('R5')) {
    console.log('R5 Chaos Monkey latency on the batch node');
    await monkey('batch', { level: 5, latencyActive: true, latencyRangeStart: 1000, latencyRangeEnd: 3000, exceptionsActive: false });
    const id = await approvedDryRun(wb, p[4], `Campaign R5 ${stamp}`);
    const j = await waitFor(wb.maker, id, ['COMPLETED', 'FAILED'], 1200);
    await monkey('batch', null);
    oneResultPerMember('R5', j, results(id));
    report.scenarios.R5 = { id, durationSeconds: j.durationSeconds };
  }

  if (wanted('R6')) {
    console.log('R6 the kill switch while a job is queued');
    const admin = await signIn('sysadmin@fci-demo.test', 'HQ001', 'SYSTEM_ADMINISTRATOR');
    const hqChecker = await signIn('checker.hq@fci-demo.test', 'HQ001', 'PAYROLL_CHECKER');
    const halt = await admin.call('POST', '/v1/jobs/halt', { reason: `Campaign R6 halt ${stamp}` });
    // 409: a halt left by an earlier, interrupted run; the scenario goes on under it.
    check('R6', 'the System Administrator at HQ halts batch processing (201)', halt.status === 201 || halt.status === 409, halt.status);
    const id = await approvedDryRun(mh, p[4], `Campaign R6 ${stamp}`);
    await sleep(15000);
    const held = await job(mh.maker, id);
    check('R6', 'a job approved during the halt is not started', ['PENDING', 'AWAITING_APPROVAL'].includes(held.status), held.status);
    const resume = await mh.maker.call('POST', `/v1/jobs/${id}/restart`, { reason: 'Campaign R6 forged restart' });
    check('R6', 'no restart is taken while halted (409)', resume.status === 409, resume.status);
    const release = await admin.call('POST', '/v1/jobs/halt/release', { reason: 'Campaign R6 release' });
    check('R6', 'the release goes to an HQ checker (202)', release.status === 202, release.status);
    const releaseId = Number(one("SELECT MAX(approval_id) FROM approval_queue WHERE status = 'PENDING_APPROVAL' AND entity_name = 'batch_halt'"));
    const decided = releaseId ? await decide(hqChecker, releaseId) : 0;
    check('R6', 'the HQ checker approves the release', decided > 0 && decided < 300, decided);
    const j = await waitFor(mh.maker, id, ['COMPLETED', 'FAILED']);
    oneResultPerMember('R6', j, results(id));
  }

  console.log('Z end state');
  check('Z', 'no job left PICKED', stuck() === 0, stuck());
  check('Z', 'no job RUNNING without a live heartbeat', Number(one("SELECT COUNT(*) FROM batch_job_request r WHERE r.job_status = 'RUNNING' AND NOT EXISTS (SELECT 1 FROM batch_job_lock l WHERE l.job_request_id = r.job_request_id AND l.heartbeat_at > CURRENT_TIMESTAMP - INTERVAL '5' MINUTE)")) === 0);
  check('Z', 'online and batch healthy', (await health('online')) === 'UP' && (await health('batch')) === 'UP');

  report.finished = new Date().toISOString();
  report.checks = checks;
  const out = resolve(ROOT, `docs/perf/batch-campaign-${report.started.slice(0, 10)}.json`);
  mkdirSync(dirname(out), { recursive: true });
  writeFileSync(out, JSON.stringify(report, null, 2));
  const failed = checks.filter((c) => !c.ok);
  console.log(`\n${checks.length - failed.length}/${checks.length} checks passed; report ${out}`);
  process.exit(failed.length ? 1 : 0);
}

main().catch(async (e) => {
  console.error(e);
  await monkey('batch', null).catch(() => {});
  process.exit(2);
});
