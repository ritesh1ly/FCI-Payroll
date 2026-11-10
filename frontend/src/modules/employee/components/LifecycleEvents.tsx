'use client';

import { useCallback, useState } from 'react';
import { HistoryButton } from '@/modules/audit';
import { todayIso, useDocumentTypes } from '@/modules/general';
import { useI18n } from '@/shared/i18n';
import { useReference } from '@/shared/reference';
import { can, useMe } from '@/shared/session';
import { Card, date, ExportButton, ServerPagination, Src, withBase } from '@/shared/ui';
import { OrderCopyCard } from '@/shared/ui/orderCopy';
import { Act, Explained, Panel, Tbl } from '@/shared/ui/wireframe';
import type { LifecycleEvent } from '../api/eventsApi';
import type { ReceivedEvent } from '../api/registerApi';
import { eventDetails, eventEffect, eventSource, statusPill, taskPill } from '../business/eventRules';
import { eventKeys, eventPages } from '../orchestration/eventQueries';
import { taskDocumentUrl, useTaskDocuments } from '../orchestration/hrmsChangeQueries';
import { registerKeys, registerPages, registerUrls } from '../orchestration/registerQueries';

/**
 * The row the order card opens on: the first whose I-21 task has passed its order entry, else the first with a task,
 * else the first. A task still PENDING_ORDER_DETAILS has no order to show (SRS-G-31).
 */
function firstWithOrder<T extends { taskId: number | null; taskStatus: string | null }>(rows: T[]): T | null {
  return (
    rows.find((r) => r.taskId && r.taskStatus !== 'PENDING_ORDER_DETAILS') ??
    rows.find((r) => r.taskId) ??
    rows[0] ??
    null
  );
}

/**
 * I-17 Employee Life-Cycle Events (from HRMS) as Wireframes v6.1 draws it (OD-121; SRS-1-63; SRS l.3866-3883), View
 * mode only: History, the link to the tasks on I-21, the X-07 fallback upload and the Suspension / Revocation Report;
 * the events received with their source, pay effect, I-21 task and status; the rules and the order of the picked event.
 * "All are read-only on I-17" and "No event is keyed in Payroll": an event arrives from HRMS (an I-21 task) or, for
 * suspension, absconding and revocation, from the X-07 fallback upload (SRS-1-72). Hold and stop of payment are M-01
 * Payroll Control Flags. Events keyed before the screen became read-only stay listed as they were (source Payroll).
 * The employee's own history is a closed section (WF-229). The HRMS collection has no life-cycle API (HRMS-PULL-1).
 */
export function LifecycleEvents() {
  const { t } = useI18n();
  const me = useMe();
  const reference = useReference();
  const [picked, setPicked] = useState<ReceivedEvent | null>(null);
  const mayExport = can(me.data, 'I-17', 'EXPORT');
  const onRows = useCallback(
    (rows: ReceivedEvent[]) => setPicked((now) => rows.find((r) => r.id === now?.id) ?? firstWithOrder(rows)),
    [],
  );
  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="spacer" />
        <HistoryButton
          entity="employee_lifecycle_event"
          recordKey={String(picked?.id ?? '')}
          label={t('screen.I-17')}
          text={t('wf.historyAct')}
          className="btn"
        />
        <Act label={t('evt.w.tasks')} href={withBase('/payroll/?s=I-21')} />
        <Act label={t('evt.w.fallback')} href={withBase('/payroll/?s=X-07')} />
        {mayExport ? (
          <ExportButton
            href={registerUrls.eventsExport(true)}
            what={t('evt.w.report')}
            label={t('evt.w.report')}
          />
        ) : (
          <Explained label={t('evt.w.report')} why={t('evt.w.why.export')} />
        )}
      </div>
      <Tbl
        title={t('evt.w.title')}
        acts={
          mayExport ? (
            <ExportButton
              href={registerUrls.eventsExport(false)}
              what={t('evt.w.title')}
              label={t('m01.w.exportShort')}
              className="btn sm"
            />
          ) : (
            <Explained label={t('m01.w.exportShort')} why={t('evt.w.why.export')} kind="sm" />
          )
        }
        cols={[
          { t: t('evt.w.col.event'), s: 'HRMS' },
          { t: t('evt.w.col.member'), s: 'HRMS' },
          { t: t('evt.w.col.effective'), s: 'HRMS' },
          { t: t('evt.w.col.details'), s: 'HRMS' },
          { t: t('evt.w.col.source'), s: 'INTF' },
          { t: t('evt.w.col.effect'), s: 'CALC' },
          { t: t('evt.w.col.task'), s: 'SYS' },
          { t: t('evt.w.col.status'), s: 'SYS' },
        ]}
      >
        <ServerPagination
          queryKey={registerKeys.events}
          fetchPage={registerPages.events}
          empty={<p className="fl-hint">{t('evt.none')}</p>}
          onRows={onRows}
        >
          {(rows) =>
            rows.map((e) => {
              const details = eventDetails(e);
              const source = eventSource(e);
              return (
                <tr key={e.id} className="fl-pick" onClick={() => setPicked(e)}>
                  <td>
                    <b>{reference.label('LIFECYCLE_EVENT_TYPE', e.eventType)}</b>
                  </td>
                  <td>
                    {e.eis} {e.employee}
                  </td>
                  <td>{date(e.effective)}</td>
                  <td>{t(details.key, details.args)}</td>
                  <td>{t(source.key, source.args)}</td>
                  <td>{t(`evt.w.effect.${eventEffect(e.eventType)}`, [date(e.effective)])}</td>
                  <td>{e.taskNo ?? '—'}</td>
                  <td>
                    <span className={`pill ${taskPill(e.taskStatus)}`}>
                      {t(`evt.w.status.${e.taskStatus ?? 'NONE'}`)}
                    </span>
                  </td>
                </tr>
              );
            })
          }
        </ServerPagination>
      </Tbl>
      <Panel
        title={t('evt.w.rules')}
        items={[
          { icon: '⬇', title: t('evt.w.rule.1'), src: 'HRMS', text: t('evt.w.rule.1m'), tone: 'info' },
          { icon: '📥', title: t('evt.w.rule.2'), src: 'INTF', text: t('evt.w.rule.2m'), tone: 'warn' },
          { icon: '🧮', title: t('evt.w.rule.3'), src: 'CALC', text: t('evt.w.rule.3m'), tone: 'info' },
        ]}
      />
      {/* Read-only on I-17 for every role (the pack's rbac.c is empty): the order is recorded on the I-21 task. */}
      <EventOrders taskId={picked?.taskId ?? null} />
      <EmployeeHistory />
    </div>
  );
}

function EventOrders({ taskId }: { taskId: number | null }) {
  const { t } = useI18n();
  const stored = useTaskDocuments(taskId ?? 0, taskId !== null);
  const types = useDocumentTypes(true);
  return (
    <OrderCopyCard
      key={taskId ?? 0}
      hint={t('evt.w.ordHint')}
      canAttach={false}
      readOnly
      stored={taskId === null ? [] : stored.data}
      storedError={stored.error ?? types.error}
      fileHref={(fileId) => taskDocumentUrl(taskId ?? 0, fileId)}
      today={todayIso()}
      attachmentRequired={false}
      referenceRequired={() => false}
      onAttach={async () => undefined}
    />
  );
}

/** One employee's events, with the link to each I-21 task (closed until pressed, WF-229). */
function EmployeeHistory() {
  const { t } = useI18n();
  const reference = useReference();
  const [lookup, setLookup] = useState('');
  const [historyOf, setHistoryOf] = useState<string | null>(null);

  const table = (rows: LifecycleEvent[]) => (
    <div className="wrap">
      <table className="t">
        <thead>
          <tr>
            <th>
              {t('evt.employee')}
              <Src s="HRMS" />
            </th>
            <th>
              {t('evt.type')}
              <Src s="HRMS" />
            </th>
            <th>
              {t('evt.date')}
              <Src s="HRMS" />
            </th>
            <th>
              {t('evt.details')}
              <Src s="HRMS" />
            </th>
            <th>
              {t('common.reason')}
              <Src s="HRMS" />
            </th>
            <th>
              {t('evt.task')}
              <Src s="SYS" />
            </th>
            <th>
              {t('evt.status')}
              <Src s="SYS" />
            </th>
          </tr>
        </thead>
        <tbody>
          {rows.map((e) => (
            <tr key={e.id}>
              <td>
                <b>{e.eis}</b>
                <div className="sub2">{e.employeeName}</div>
              </td>
              <td>{reference.label('LIFECYCLE_EVENT_TYPE', e.eventType)}</td>
              <td>
                {date(e.eventDate)}
                {e.endDate ? ` – ${date(e.endDate)}` : ''}
              </td>
              <td>
                {[
                  e.subsistencePercent !== null ? t('evt.subsistenceValue', [e.subsistencePercent]) : null,
                  e.releaseFromYymm ? `${e.releaseFromYymm}–${e.releaseToYymm ?? ''}` : null,
                  e.interveningPeriod ? t('evt.intervening', [e.interveningPeriod]) : null,
                  e.orderReference,
                ]
                  .filter(Boolean)
                  .join(' · ') || '—'}
                <div className="sub2">
                  {t(`evt.source.${e.source}`)} · {e.createdBy}
                </div>
              </td>
              <td>{e.reason}</td>
              <td>
                {e.hrmsChangeTaskId !== null ? (
                  <a href={withBase(`/payroll/?s=I-21&task=${e.hrmsChangeTaskId}`)}>
                    {t('evt.taskLink', [e.hrmsChangeTaskId])}
                  </a>
                ) : (
                  '—'
                )}
                {e.hrmsRequestId && <div className="sub2">{e.hrmsRequestId}</div>}
              </td>
              <td>
                <span className={`pill ${statusPill(e.status)}`}>{t(`status.${e.status}`)}</span>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );

  return (
    <details className="fl-mt">
      <summary>{t('evt.employeeHistory')}</summary>
      <Card title={t('evt.recent')}>
        <ServerPagination
          queryKey={eventKeys.recentEvents}
          fetchPage={eventPages.recentEvents}
          empty={<p className="fl-hint">{t('evt.none')}</p>}
        >
          {(rows) => table(rows)}
        </ServerPagination>
      </Card>
      <Card title={t('evt.employeeHistory')}>
        <div className="bd rf-grid">
          <div className="rf-field">
            <label htmlFor="evt-lookup">{t('evt.eis')}</label>
            <input
              id="evt-lookup"
              maxLength={20}
              value={lookup}
              onChange={(e) => setLookup(e.target.value)}
            />
          </div>
          <div className="rf-field">
            <button
              type="button"
              className="btn"
              disabled={lookup.trim() === ''}
              onClick={() => setHistoryOf(lookup.trim())}
            >
              {t('evt.show')}
            </button>
          </div>
        </div>
        {historyOf && (
          <ServerPagination queryKey={eventKeys.events(historyOf)} fetchPage={eventPages.events(historyOf)}>
            {(rows) => table(rows)}
          </ServerPagination>
        )}
      </Card>
    </details>
  );
}
