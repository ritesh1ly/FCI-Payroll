'use client';

import type { ReactNode } from 'react';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { dateTime, ExportButton, ServerPagination, StatusPill } from '@/shared/ui';
import { Explained, Panel, type PanelItem, Tbl } from '@/shared/ui/wireframe';
import type { ActiveUser, ActiveUserPage, ScreenAccess } from '../api/workflowMasterApi';
import type { Cell, X01Panel, X01Table } from './x01Content';
import { X01_TABLES } from './x01Content';
import { userFlag } from '../business/x01Rules';
import {
  accessMatrixKey,
  activeUsersExportUrl,
  activeUsersKey,
  workflowPages,
} from '../orchestration/workflowQueries';

/** The drawn columns of the Screen-Level Access Matrix, in order, by role code. */
const MATRIX_ROLES = [
  'PAYROLL_MAKER',
  'PAYROLL_CHECKER',
  'LABOUR_DEALING_HAND',
  'ESTABLISHMENT_HR',
  'FINANCE',
  'SYSTEM_ADMINISTRATOR',
  'EMPLOYEE_ESEVA',
] as const;

const LETTER_TONE: Record<string, string> = { A: 'p-ok', E: 'p-info', V: 'p-pur' };

/** The columns of a drawn table with their labels. */
export function useColumns(table: X01Table) {
  const { t } = useI18n();
  return table.cols.map((c) => ({ t: t(c.t), s: c.s, num: c.num }));
}

function CellText({ cell }: { cell: Cell }) {
  const { t } = useI18n();
  if (typeof cell === 'string') return <>{cell === '—' ? cell : t(cell)}</>;
  const text = cell.raw ?? (cell.k ? t(cell.k) : '');
  return (
    <>
      {cell.pill ? <span className="pill p-ok">{text}</span> : cell.strong ? <b>{text}</b> : text}
      {cell.tail && (
        <>
          {' '}
          <b>{t(cell.tail)}</b>
        </>
      )}
      {cell.sub && (
        <>
          {' '}
          <span className="sub2">{t(cell.sub)}</span>
        </>
      )}
    </>
  );
}

/** A drawn reference table: title, header actions, columns with badges, read-only rows, its note. */
export function ReferenceTable({ table, acts }: { table: X01Table; acts?: ReactNode }) {
  const { t } = useI18n();
  const cols = useColumns(table);
  return (
    <Tbl
      title={t(table.title)}
      acts={acts}
      cols={cols}
      after={
        table.note && (
          <div className="bd fl-pad-top-0">
            <div className="oq">
              {table.note.lead && <b>{t(table.note.lead)}</b>} {t(table.note.text)}
            </div>
          </div>
        )
      }
    >
      {(table.rows ?? []).map((row, r) => (
        <tr key={r}>
          {row.map((cell, c) => (
            <td key={c} className={table.cols[c]?.num ? 'num' : undefined}>
              <CellText cell={cell} />
            </td>
          ))}
        </tr>
      ))}
    </Tbl>
  );
}

/** A drawn panel; {@code rightOf} puts a live figure at the right of the item with that title key. */
export function ReferencePanel({
  panel,
  rightOf = {},
}: {
  panel: X01Panel;
  rightOf?: Record<string, ReactNode>;
}) {
  const { t } = useI18n();
  const items: PanelItem[] = panel.items.map((i) => ({
    icon: i.icon,
    tone: i.tone,
    src: i.src,
    title: t(i.title),
    text: t(i.text),
    ...(i.title in rightOf ? { right: rightOf[i.title] } : {}),
  }));
  return (
    <Panel
      title={t(panel.title)}
      items={items}
      after={
        panel.note && (
          <div className="bd fl-pad-top-0">
            <div className="oq">{t(panel.note)}</div>
          </div>
        )
      }
    />
  );
}

function AccessLetter({ letter }: { letter: string | undefined }) {
  return <span className={`pill ${letter ? LETTER_TONE[letter] : 'p-gray'}`}>{letter ?? '—'}</span>;
}

function MatrixRow({ row }: { row: ScreenAccess }) {
  const { t } = useI18n();
  return (
    <tr>
      <td>
        <b>{row.screenCode}</b> {t(`screen.${row.screenCode}`)}
      </td>
      {MATRIX_ROLES.map((role) => (
        <td key={role}>
          <AccessLetter letter={row.access[role]} />
        </td>
      ))}
    </tr>
  );
}

/**
 * The Screen-Level Access Matrix (SRS-22-8; the X-01 design: it generates the menu): the grants in force of the drawn
 * roles, one letter per screen (V view, E edit, A approve, a dash no access), paged on the server. A change is made
 * on A-02, under maker-checker with an OTP.
 */
export function ScreenMatrix({ acts }: { acts: ReactNode }) {
  const { t } = useI18n();
  const cols = useColumns(X01_TABLES.screenMatrix);
  return (
    <Tbl title={t(X01_TABLES.screenMatrix.title)} acts={acts} cols={cols}>
      <ServerPagination
        queryKey={accessMatrixKey}
        fetchPage={workflowPages.accessMatrix}
        personal={false}
        empty={<p className="fl-hint">{t('x01.noScreens')}</p>}
      >
        {(rows) => rows.map((row) => <MatrixRow key={row.screenCode} row={row} />)}
      </ServerPagination>
    </Tbl>
  );
}

function UserRow({ user, threshold }: { user: ActiveUser; threshold: number | null }) {
  const { t } = useI18n();
  const flag = userFlag(user);
  return (
    <tr>
      <td>
        <b>{user.loginId}</b>
      </td>
      <td>
        {user.access.length === 0
          ? '—'
          : user.access.map((a) => <div key={a.groupCode}>{t(`role.${a.roleCode}`)}</div>)}
      </td>
      <td>
        {user.access.length === 0
          ? '—'
          : user.access.map((a) => (
              <div key={a.groupCode}>
                {a.unit === 'ALL' ? t('a01.report.allOffices') : (a.unit ?? '—')} / {a.groupCode}
              </div>
            ))}
      </td>
      <td>{user.lastLoginAt ? dateTime(user.lastLoginAt) : '—'}</td>
      <td>
        {flag === 'NEVER' && <span className="pill p-warn">{t('x01.flag.never')}</span>}
        {flag === 'DORMANT' && <span className="pill p-bad">{t('x01.flag.dormant', [threshold])}</span>}
        {flag === 'STATUS' && <StatusPill status={user.status} />}
      </td>
    </tr>
  );
}

/**
 * The Active User Report (SRS-G-50): user ID, role, unit / Access Group, last login and the flag, in the session's
 * scope, paged on the server. The dormant flag follows the A-07 setting INACTIVE_USER_DAYS and is not shown while FCI
 * has not set it. The export is A-01 EXPORT: drawn for everyone, it says why for a role without it (OD-125).
 */
export function ActiveUsers() {
  const { t } = useI18n();
  const me = useMe();
  const table = X01_TABLES.activeUsers;
  const cols = useColumns(table);
  return (
    <Tbl
      title={t(table.title)}
      cols={cols}
      acts={
        can(me.data, 'A-01', 'EXPORT') ? (
          <ExportButton
            className="btn sm"
            href={activeUsersExportUrl()}
            what={t(table.title)}
            label={t('x01.export')}
          />
        ) : (
          <Explained label={t('x01.export')} why={t('reason.noExport')} kind="sm" />
        )
      }
    >
      {can(me.data, 'A-01', 'VIEW') ? (
        <ServerPagination<ActiveUserPage>
          queryKey={activeUsersKey}
          fetchPage={workflowPages.activeUsers}
          empty={<p className="fl-hint">{t('a01.none')}</p>}
        >
          {(rows, page) =>
            rows.map((user) => <UserRow key={user.userId} user={user} threshold={page.inactiveAfterDays} />)
          }
        </ServerPagination>
      ) : (
        // SRS-G-50: the report is the administrators'. The table stays as drawn; its data does not.
        <tr>
          <td colSpan={cols.length} className="fl-hint">
            {t('x01.why.activeUsers')}
          </td>
        </tr>
      )}
    </Tbl>
  );
}
