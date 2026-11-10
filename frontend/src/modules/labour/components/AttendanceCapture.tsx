'use client';

import { useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { businessToday } from '@/shared/time';
import { Card, Empty, Problem, Src, date, useToast } from '@/shared/ui';
import type { PayrollType } from '../api/labourApi';
import {
  PAYROLL_TYPES,
  dayBlocked,
  dayStrength,
  daysOf,
  frozenDay,
  markable,
  previousMonth,
} from '../business/labourRules';
import { useAttendance, useOptions, useSaveDay, useSubmitBatch } from '../orchestration/labourQueries';
import { GangPicker } from './GangPicker';

/**
 * The month grid of I-01 Labour Attendance Capture, kept whole in the closed Day-wise grid section of
 * the screen as drawn (plan 20 phase 20b; SRS-2-2, SRS-2-29, SRS-2-35, SRS-2-38, SRS-3-1):
 * the Labour Dealing Hand chooses the gang and the month, and marks a day for every member of the
 * gang that day in the code set of the gang's payroll type (the day opens blank). The month's days
 * are counted from the marks (never typed). When the month is over the month is submitted as one
 * batch to the Payroll Checker of the Divisional Office; a returned batch may be marked again.
 * The gang is searched on the server among the active gangs of the session's depots, the depot's
 * Ancillary Pool included (SRS-2-46: attendance for each person in the pool; it has no strength).
 * The Gang Strength Check shows the open day's present strength against the Sanctioned Strength
 * and the shortfall (l.4545, SRS-2-48).
 */
export function AttendanceMonthGrid({
  engine = null,
  initialGang = null,
}: {
  /** The payroll type the view opened with; null lists every type. */
  engine?: PayrollType | null;
  /** The gang a row's Edit opened the section on. */
  initialGang?: number | null;
}) {
  const { t } = useI18n();
  const me = useMe();
  const toast = useToast();
  const today = businessToday();
  const options = useOptions();
  // I-01 l.4539-4541: the depot (Access Group scope) and the payroll type narrow the gangs.
  const [depot, setDepot] = useState<number | null>(null);
  const [payrollType, setPayrollType] = useState<PayrollType | ''>(engine ?? '');
  const [gangId, setGangId] = useState<number | null>(initialGang);
  const [month, setMonth] = useState(today.slice(0, 7));
  const grid = useAttendance(gangId, month);
  const saveDay = useSaveDay();
  const submit = useSubmitBatch();
  const [day, setDay] = useState('');
  const [marks, setMarks] = useState<Record<number, string>>({});
  const [error, setError] = useState<unknown>(null);
  const mayMark = can(me.data, 'I-01', 'CREATE');
  const maySubmit = can(me.data, 'I-01', 'SUBMIT');
  const data = grid.data;
  const days = data ? daysOf(month, data.lastMarkable) : [];
  const strength = data && day ? dayStrength(data, marks) : null;
  const dayWhy = !mayMark ? 'reason.ldhOnly' : data && day ? dayBlocked(data, day, marks) : 'i01.why.day';
  const monthOver = month < today.slice(0, 7);
  const submitWhy = !maySubmit
    ? 'reason.ldhOnly'
    : !monthOver
      ? 'i01.why.monthOpen'
      : data && (data.batchState === 'SUBMITTED' || data.batchState === 'APPROVED')
        ? 'i01.why.batch'
        : null;

  function openDay(next: string) {
    setDay(next);
    setError(null);
    const current: Record<number, string> = {};
    for (const m of data?.members ?? []) {
      const mark = m.marks.find((x) => x.date === next && !x.otherGang);
      if (mark) current[m.employeeId] = mark.code;
    }
    setMarks(current);
  }

  async function save() {
    if (gangId === null) return;
    setError(null);
    try {
      const saved = await saveDay.mutateAsync({ gangId, date: day, marks });
      toast(t('i01.saved', [day]));
      if (saved.markedAbsent.length > 0) toast(t('i01.markedAbsent', [saved.markedAbsent.join(', ')]));
    } catch (cause) {
      setError(cause);
    }
  }

  async function sendBatch() {
    if (gangId === null) return;
    setError(null);
    try {
      await submit.mutateAsync({ gangId, month });
      toast(t('i01.submitted'));
    } catch (cause) {
      setError(cause);
    }
  }

  return (
    <div className="wireframe-body-container">
      <Card title={t('screen.I-01')}>
        <div className="toolbar">
          <label>
            {t('m02.depot')}
            <Src s="ENT" />{' '}
            <select
              value={depot ?? ''}
              onChange={(e) => {
                setDepot(e.target.value ? Number(e.target.value) : null);
                setGangId(null);
                setDay('');
              }}
            >
              <option value="">{t('i01.anyDepot')}</option>
              {(options.data?.depots ?? []).map((d) => (
                <option key={d.id} value={d.id}>
                  {d.code} - {d.name}
                </option>
              ))}
            </select>
          </label>
          <label>
            {t('m02.payrollType')}
            <Src s="ENT" />{' '}
            <select
              value={payrollType}
              onChange={(e) => {
                setPayrollType(e.target.value as PayrollType | '');
                setGangId(null);
                setDay('');
              }}
            >
              <option value="">{t('i01.anyType')}</option>
              {PAYROLL_TYPES.map((p) => (
                <option key={p} value={p}>
                  {t(`labour.type.${p}`)}
                </option>
              ))}
            </select>
          </label>
          <GangPicker
            key={`${depot ?? ''}-${payrollType}`}
            label={t('m09.gang')}
            value={gangId}
            search={{ status: 'ACTIVE', depot: depot ?? undefined, payrollType: payrollType || undefined }}
            onChange={(g) => {
              setGangId(g ? g.gangId : null);
              setDay('');
            }}
          />
          <label>
            {t('i01.month')}{' '}
            <input
              type="month"
              value={month}
              max={today.slice(0, 7)}
              onChange={(e) => {
                setMonth(e.target.value);
                setDay('');
              }}
            />
          </label>
          <button type="button" className="btn" onClick={() => setMonth(previousMonth(today))}>
            {t('i01.lastMonth')}
          </button>
        </div>
        {gangId === null && <Empty text={t('i01.chooseGang')} />}
        {data && (
          <>
            <p className="sub2" role="status">
              {data.gangName} · {data.depot} · {t(`labour.type.${data.payrollType}`)} ·{' '}
              {t(`i01.batch.${data.batchState}`)}
              {data.sanctionedStrength !== null && ` · ${t('i01.sanctioned', [data.sanctionedStrength])}`}
            </p>
            {/* SRS-2-36 (I-01 l.4543): the depot's Attendance Source, read-only from the HQ master. */}
            <p className="sub2" aria-label={t('i01.source')}>
              {t('i01.source')}
              <Src s="SYS" />:{' '}
              {data.attendanceSource
                ? [
                    t(`i01.mode.${data.attendanceSource.mode}`),
                    t(`i01.sourceStatus.${data.attendanceSource.status}`),
                    t('i01.sourceFrom', [date(data.attendanceSource.effectiveFrom)]),
                    ...(data.attendanceSource.annaDarpan ? [t('i01.annaDarpan')] : []),
                    ...(data.attendanceSource.fallbackToManual ? [t('i01.fallbackManual')] : []),
                  ].join(' · ')
                : t('i01.sourceNotConfigured')}
            </p>
            {data.batchState === 'RETURNED' && data.returnReason && (
              <p className="sub2" role="note" aria-label={t('i01.returnReason')}>
                {t('i01.returnedWith', [data.returnReason])}
              </p>
            )}
            <table className="t" aria-label={t('i01.grid')}>
              <thead>
                <tr>
                  <th>
                    {t('m02.code')}
                    <Src s="SYS" />
                  </th>
                  <th>
                    {t('m09.role')}
                    <Src s="ENT" />
                  </th>
                  {days.map((d) => (
                    <th key={d}>
                      <button
                        type="button"
                        className="btn"
                        onClick={() => openDay(d)}
                        aria-label={t('i01.markDay', [d])}
                      >
                        {d.slice(8)}
                      </button>
                      <Src s="ENT" />
                    </th>
                  ))}
                  <th>
                    {t('i01.present')}
                    <Src s="CALC" />
                  </th>
                  <th>
                    {t('i01.leave')}
                    <Src s="CALC" />
                  </th>
                  <th>
                    {t('i01.absent')}
                    <Src s="CALC" />
                  </th>
                </tr>
              </thead>
              <tbody>
                {data.members.map((m) => (
                  <tr key={m.employeeId}>
                    <td>
                      {m.labourCode}
                      <div className="sub2">{m.name}</div>
                    </td>
                    <td>{t(`m09.role.${m.role}`)}</td>
                    {days.map((d) => {
                      const mark = m.marks.find((x) => x.date === d);
                      const frozen = mark !== undefined && !mark.otherGang && mark.workSlipFiled;
                      return (
                        <td
                          key={d}
                          className={frozen ? 'frozen' : undefined}
                          title={mark?.otherGang ? t('i01.otherGang') : frozen ? t('i01.frozen') : undefined}
                        >
                          {mark ? (mark.otherGang ? `(${mark.code})` : mark.code) : markable(m, d) ? '·' : ''}
                          {frozen && '*'}
                        </td>
                      );
                    })}
                    <td>{m.presentDays}</td>
                    <td>{m.leaveDays}</td>
                    <td>{m.absentDays}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            {day && (
              <Card title={t('i01.dayOf', [day])}>
                <table className="t" aria-label={t('i01.dayOf', [day])}>
                  <thead>
                    <tr>
                      <th>
                        {t('m02.name')}
                        <Src s="ENT" />
                      </th>
                      <th>
                        {t('i01.code')}
                        <Src s="ENT" />
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.members
                      .filter((m) => markable(m, day))
                      .map((m) => (
                        <tr key={m.employeeId}>
                          <td>
                            {m.labourCode} - {m.name}
                          </td>
                          <td>
                            <select
                              aria-label={t('i01.codeOf', [m.labourCode])}
                              value={marks[m.employeeId] ?? ''}
                              onChange={(e) => setMarks({ ...marks, [m.employeeId]: e.target.value })}
                            >
                              <option value="">—</option>
                              {data.codes.map((c) => (
                                <option key={c.code} value={c.code}>
                                  {c.code} - {c.meaning}
                                </option>
                              ))}
                            </select>
                          </td>
                        </tr>
                      ))}
                  </tbody>
                </table>
                <div className="toolbar">
                  <button
                    type="button"
                    className="btn primary"
                    disabled={dayWhy !== null || saveDay.isPending}
                    title={dayWhy ? t(dayWhy) : undefined}
                    onClick={() => void save()}
                  >
                    {t('action.i01.save')}
                  </button>
                </div>
                {strength && (
                  <p className="sub2" role="status" aria-label={t('i01.strengthCheck')}>
                    {t('i01.strength', [strength.present, strength.sanctioned])}
                    {strength.short > 0 && ` · ${t('i01.short', [strength.short])}`}
                  </p>
                )}
                {dayWhy && <p className="sub2">{t(dayWhy)}</p>}
              </Card>
            )}
            {days.some((d) => frozenDay(data, d)) && (
              <p className="sub2" role="note">
                {t('i01.frozenNote')}
              </p>
            )}
            <div className="toolbar">
              {/* SRS-2-39 (PENDING LBR-20b-1) and SRS-2-57 (PENDING LBR-20b-3): shown with the reason. */}
              <button type="button" className="btn" disabled title={t('i01.why.correction')}>
                {t('action.i01.correct')}
              </button>
              <button type="button" className="btn" disabled title={t('i01.why.leave')}>
                {t('action.i01.leave')}
              </button>
              <button
                type="button"
                className="btn primary"
                disabled={submitWhy !== null || submit.isPending}
                title={submitWhy ? t(submitWhy) : undefined}
                onClick={() => void sendBatch()}
              >
                {t('action.i01.submit')}
              </button>
            </div>
          </>
        )}
        <Problem error={error ?? grid.error} />
      </Card>
    </div>
  );
}
