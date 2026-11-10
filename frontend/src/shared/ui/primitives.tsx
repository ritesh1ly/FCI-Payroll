'use client';

import type { ReactNode } from 'react';
import { ApiError } from '@/shared/api';
import { useI18n } from '@/shared/i18n';

/** Live wireframe pills: the status value picks the colour, its label comes from the bundle. */
const PILL: Record<string, string> = {
  ACTIVE: 'p-ok',
  APPROVED: 'p-ok',
  CHANGE_PENDING: 'p-warn',
  PENDING_APPROVAL: 'p-warn',
  PAYROLL_SETUP_PENDING: 'p-info',
  PAN_CPF_MISSING: 'p-bad',
  BANK_INVALID: 'p-bad',
  REJECTED: 'p-bad',
  QUEUED: 'p-info',
  PENDING: 'p-warn',
  EXPIRED: 'p-gray',
  RUNNING: 'p-warn',
  SUCCEEDED: 'p-ok',
  FAILED: 'p-bad',
  RETURNED: 'p-pur',
  SUSPENDED: 'p-warn',
  LOCKED: 'p-bad',
  DEACTIVATED: 'p-gray',
  PROVISIONAL: 'p-info',
  SETTLED: 'p-ok',
  RETIRED: 'p-gray',
  NONE: 'p-gray',
};

export function StatusPill({ status }: { status: string }) {
  const { t } = useI18n();
  return <span className={`pill ${PILL[status] ?? 'p-gray'}`}>{t(`status.${status}`)}</span>;
}

export function Card({
  title,
  children,
  actions,
}: {
  title: string;
  children: ReactNode;
  actions?: ReactNode;
}) {
  return (
    <div className="card">
      <div className="hd">
        <h3>{title}</h3>
        <span className="sp" />
        {actions}
      </div>
      {children}
    </div>
  );
}

export function Tile({
  icon,
  label,
  value,
  sub,
  tone,
}: {
  icon: string;
  label: string;
  value: ReactNode;
  sub?: string;
  tone?: 'ok' | 'bad' | 'info' | 'warn';
}) {
  return (
    <div className="tile">
      <div className="lab">
        <span className={`tico fl-tico-${tone ?? 'info'}`} aria-hidden="true">
          {icon}
        </span>
        {label}
      </div>
      <div className={`big ${tone === 'bad' ? 'fl-bad' : ''}`}>{value}</div>
      {sub && <div className="sub">{sub}</div>}
    </div>
  );
}

/**
 * SRS-EH-2, SRS-EH-3 (US-GAP-22): the corrective action of a refusal from the message catalogue: the
 * message's own action text when it has one, else the action of its HTTP status; none for a
 * failure that never reached the server.
 */
export function correctiveAction(
  t: (key: string, args?: readonly unknown[]) => string,
  problem: ApiError,
): string | null {
  if (problem.status === 0) return null;
  if (problem.actionKey) {
    const own = t(problem.actionKey, problem.args);
    if (own !== problem.actionKey) return own;
  }
  const byStatus = `error.action.${problem.status}`;
  const text = t(byStatus);
  return text === byStatus ? null : text;
}

export function Problem({ error }: { error: unknown }) {
  const { t } = useI18n();
  if (!error) return null;
  const problem = error instanceof ApiError ? error : new ApiError(0, {}, undefined);
  const action = correctiveAction(t, problem);
  return (
    <p className="fl-error" role="alert" data-severity={problem.severity}>
      {t(problem.messageKey, problem.args)}
      {action && (
        <span className="fl-action">
          {t('error.action.label')} {action}
        </span>
      )}
      {problem.correlationId && (
        <span className="fl-ref">{t('error.reference', [problem.correlationId])}</span>
      )}
    </p>
  );
}

export function Empty({ text }: { text: string }) {
  return <div className="fl-empty">{text}</div>;
}

/** A calendar date (yyyy-mm-dd), e.g. a version's effective date: day-month-year, no time. */
export function date(value: string | null | undefined): string {
  if (!value) return '—';
  const [y, m, d] = value.slice(0, 10).split('-').map(Number);
  if (!y || !m || !d) return value;
  return new Date(Date.UTC(y, m - 1, d)).toLocaleDateString('en-IN', {
    day: '2-digit',
    month: 'short',
    year: 'numeric',
    timeZone: 'UTC',
  });
}

/**
 * The calendar date of an instant (a timestamp with zone) in India (Asia/Kolkata), day-month-year:
 * for a field the SRS gives as a Date that the server keeps as a timestamp (e.g. a "Received"
 * date). Never the UTC date, which is a day early after 18:30 IST.
 */
export function dayOf(value: string | null | undefined): string {
  if (!value) return '—';
  const instant = new Date(value);
  if (Number.isNaN(instant.getTime())) return value;
  return instant.toLocaleDateString('en-IN', {
    day: '2-digit',
    month: 'short',
    year: 'numeric',
    timeZone: 'Asia/Kolkata',
  });
}

/** Data value formatting (dates are not translated; Indian day-month-year order). */
export function dateTime(value: string | null | undefined): string {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString('en-IN', {
    day: '2-digit',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
    timeZone: 'Asia/Kolkata',
  });
}

export function amount(value: number | null | undefined): string {
  return value === null || value === undefined
    ? '—'
    : value.toLocaleString('en-IN', { maximumFractionDigits: 2 });
}

export function errorMessage(t: (key: string, args?: readonly unknown[]) => string, cause: unknown): string {
  return cause instanceof ApiError ? t(cause.messageKey, cause.args) : t('error.unexpected');
}

/** approval_queue.entity_name as a label; governed wireframe actions are "screen:<code>". */
export function entityLabel(t: (key: string, args?: readonly unknown[]) => string, name: string): string {
  return name.startsWith('screen:')
    ? t('entity.screen', [name.slice('screen:'.length)])
    : t(`entity.${name}`);
}
