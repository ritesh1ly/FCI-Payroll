'use client';

import { useCallback, useState } from 'react';
import { HistoryButton } from '@/modules/audit';
import { todayIso, useDocumentTypes } from '@/modules/general';
import { useI18n } from '@/shared/i18n';
import { useReference } from '@/shared/reference';
import { can, useMe } from '@/shared/session';
import {
  amount,
  date,
  Empty,
  ExportButton,
  Problem,
  ServerPagination,
  Src,
  type Source,
  StatusPill,
  useDialogFocus,
  useToast,
  TextAreaField,
} from '@/shared/ui';
import { OrderCopyCard } from '@/shared/ui/orderCopy';
import {
  Act,
  Explained,
  KvCard,
  type KvRow,
  Locked,
  Panel,
  type PanelItem,
  RowActs,
  Tbl,
  Tiles,
  Toolbar,
  WfTile,
} from '@/shared/ui/wireframe';
import type { Detail, EmployeeRow } from '../api/employeeApi';
import { statusCounts } from '../business/employeeRules';
import {
  employeeKeys,
  employeePages,
  useEmployee,
  useEmployeeCounts,
  useSubmitOnboarding,
} from '../orchestration/employeeQueries';
import {
  registerKeys,
  registerPages,
  registerUrls,
  useAttachEmployeeOrder,
  useEmployeeOrders,
  useOffices,
  useRecordExtras,
} from '../orchestration/registerQueries';
import { AllowanceFlags } from './AllowanceFlags';
import { ControlFlags } from './ControlFlags';

/** The pattern filter as drawn (the list PAY_PATTERN carries the labels' codes, OD-123). */
const DRAWN_PATTERNS = ['IDA', 'CDA'];

type Mode = { eis: string; edit: boolean };

/**
 * M-01 Employee Payroll Master as Wireframes v6.1 draws it (OD-121; SRS l.3715, SRS-1-11): History and Edit Record over
 * the screen; office, pattern and status filters, search and Export; the four tiles over the whole filtered scope; the
 * list with its row actions; Record Detail; Master Validations; How a new joiner reaches this screen; Member ID History
 * (SRS-1-76); Staff Number History (SRS-16-7); the pay-status table, the employee rules and the order card. The list is
 * bounded by the session's data scope in the API query; a record outside it answers 403. No field of the record is keyed
 * here: contact, bank, PAN and pay are HRMS-owned and change only through an I-21 task (R-SRC, SRS-G-45). Establishment
 * / HR proposes the Payroll-owned flags and submits a record in Payroll Setup Pending (with the CDA Staff Code of a CDA
 * member) from the record's Edit, which reach it only after a Payroll Checker approves (SRS-1-11, SRS-1-12). Record
 * Detail shows the live read of HRMS through the gateway beside Payroll's copy (SRS-1-70; PENDING API-16-7); the list
 * reads Payroll's copy: no HRMS list API exists (HRMS-PULL-1).
 */
export function EmployeeMaster() {
  const { t } = useI18n();
  const me = useMe();
  const toast = useToast();
  const { options } = useReference();
  const offices = useOffices();
  const [query, setQuery] = useState('');
  const [pattern, setPattern] = useState('');
  const [status, setStatus] = useState('');
  const [office, setOffice] = useState('');
  const [fnd, setFnd] = useState(false);
  const [first, setFirst] = useState<EmployeeRow | null>(null);
  const [opened, setOpened] = useState<Mode | null>(() => {
    if (typeof window === 'undefined') return null;
    const eis = new URLSearchParams(window.location.search).get('eis');
    return eis ? { eis, edit: false } : null;
  });
  const effective = fnd ? 'FND' : pattern;
  const filter = { q: query, payPattern: effective, status, office };
  const tally = useEmployeeCounts(query, effective, office);
  const counts = statusCounts(tally.data);
  const mayEdit = can(me.data, 'M-01', 'FLAG');
  const mayExport = can(me.data, 'M-01', 'EXPORT');
  const mayApprove = can(me.data, 'M-01', 'APPROVE');
  const onRows = useCallback((rows: EmployeeRow[]) => setFirst(rows[0] ?? null), []);
  const openFirst = () => {
    if (first) setOpened({ eis: first.eis, edit: true });
    else toast(t('m01.none'));
  };
  const why = t('m01.w.why.edit');

  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="spacer" />
        <HistoryButton
          entity="employee"
          recordKey={first?.eis ?? ''}
          label={first ? `${first.eis} · ${first.name}` : t('screen.M-01')}
          text={t('wf.historyAct')}
          className="btn"
        />
        {mayEdit ? (
          <Act label={t('m01.w.editRecord')} kind="pri" onClick={openFirst} />
        ) : (
          <Locked label={t('m01.w.editRecord')} why={why} />
        )}
      </div>
      <Toolbar
        search={query}
        onSearch={setQuery}
        exportControl={
          mayExport ? (
            <ExportButton
              href={registerUrls.masterExport(filter)}
              what={t('screen.M-01')}
              label={t('wf.export')}
            />
          ) : (
            <Explained label={t('wf.export')} why={t('m01.w.why.export')} />
          )
        }
      >
        <select aria-label={t('m01.w.allOffices')} value={office} onChange={(e) => setOffice(e.target.value)}>
          <option value="">{t('m01.w.allOffices')}</option>
          {(Array.isArray(offices.data) ? offices.data : []).map((o) => (
            <option key={o.code} value={o.code}>
              {o.label}
            </option>
          ))}
        </select>
        <select
          aria-label={t('m01.payPattern')}
          value={pattern}
          onChange={(e) => {
            setFnd(false);
            setPattern(e.target.value);
          }}
        >
          <option value="">{t('m01.w.allPatterns')}</option>
          {options('PAY_PATTERN')
            .filter((o) => DRAWN_PATTERNS.includes(o.value))
            .map((o) => (
              <option key={o.value} value={o.value}>
                {t(`m01.w.pattern.${o.value}`)}
              </option>
            ))}
        </select>
        <select aria-label={t('m01.status')} value={status} onChange={(e) => setStatus(e.target.value)}>
          <option value="">{t('m01.allStatuses')}</option>
          {options('EMPLOYEE_RECORD_STATUS').map((o) => (
            <option key={o.value} value={o.value}>
              {o.value === 'PAYROLL_SETUP_PENDING' ? t('m01.w.setupOption') : o.label}
            </option>
          ))}
        </select>
      </Toolbar>
      {/* WF-225: FND members are not drawn in the pattern filter; the filter stays, closed until pressed. */}
      <details className="fl-mt">
        <summary>{t('m01.w.moreFilters')}</summary>
        <label className="fl-check">
          <input type="checkbox" checked={fnd} onChange={(e) => setFnd(e.target.checked)} />
          {t('m01.w.fndOnly')}
        </label>
      </details>
      <Tiles>
        <WfTile
          icon="👥"
          label={t('m01.w.total')}
          src="HRMS"
          big={tally.data?.total ?? '—'}
          sub={t('m01.w.totalSub')}
        />
        <WfTile
          icon="🆕"
          label={t('status.PAYROLL_SETUP_PENDING')}
          src="SYS"
          big={counts.setup}
          sub={t('m01.w.setupSub')}
          tone="info"
        />
        <WfTile
          icon="✅"
          label={t('m01.w.active')}
          src="SYS"
          big={counts.active}
          sub={t('m01.w.activeSub')}
          tone="ok"
        />
        <WfTile
          icon="⚠️"
          label={t('m01.exceptions')}
          src="SYS"
          big={counts.exceptions}
          sub={t('m01.w.exceptionsSub')}
          tone="bad"
          bad
        />
      </Tiles>
      <Problem error={tally.error} />
      <Tbl
        title={t('screen.M-01')}
        acts={
          mayEdit ? (
            <Explained label={t('m01.w.bulk')} why={t('m01.w.why.bulk')} kind="sm" />
          ) : (
            <Locked label={t('m01.w.bulk')} why={t('m01.w.why.bulkRole')} kind="sm" />
          )
        }
        cols={[
          { t: t('m01.eis'), s: 'HRMS' },
          { t: t('m01.name'), s: 'HRMS' },
          { t: t('m01.pan'), s: 'HRMS' },
          { t: t('m01.w.col.pattern'), s: 'CALC' },
          { t: t('m01.w.col.scale'), s: 'HRMS' },
          { t: t('m01.w.col.basic'), s: 'HRMS', num: true },
          { t: t('m01.w.col.city'), s: 'CALC' },
          { t: t('m01.w.col.cpf'), s: 'HRMS' },
          { t: t('m01.w.col.bank'), s: 'HRMS' },
          { t: t('m01.status'), s: 'SYS' },
          { t: t('m01.w.col.actions'), s: 'SYS' },
        ]}
      >
        <ServerPagination
          queryKey={employeeKeys.list(filter)}
          fetchPage={employeePages.list(filter)}
          empty={<Empty text={t('m01.none')} />}
          onRows={onRows}
        >
          {(rows) =>
            rows.map((row) => (
              <tr key={row.eis}>
                <td>
                  <b>{row.eis}</b>
                </td>
                <td>{row.name}</td>
                <td title={t('m01.w.panInRecord')}>—</td>
                <td>{row.payPattern ?? '—'}</td>
                <td>{row.payScale ?? '—'}</td>
                <td className="num">{row.basic === null ? '—' : amount(row.basic)}</td>
                <td>{row.city ?? '—'}</td>
                <td>{row.cpfCode ?? '—'}</td>
                <td>{row.bank ?? '—'}</td>
                <td>
                  <StatusPill status={row.status} />
                </td>
                <td>
                  <RowActs
                    viewName={t('m01.w.viewOf', [row.eis])}
                    editName={t('m01.w.editOf', [row.eis])}
                    onView={() => setOpened({ eis: row.eis, edit: false })}
                    onEdit={mayEdit ? () => setOpened({ eis: row.eis, edit: true }) : undefined}
                    editWhy={t('m01.w.why.editRow')}
                    onApprove={mayApprove ? () => toast(t('m01.w.approveInInbox')) : undefined}
                    cancel
                    onCancel={mayEdit ? () => toast(t('m01.w.why.cancel')) : undefined}
                    cancelWhy={t('m01.w.why.cancelRole')}
                  />
                </td>
              </tr>
            ))
          }
        </ServerPagination>
      </Tbl>
      {first && <DrawnRecord eis={first.eis} mayEdit={mayEdit} onEdit={openFirst} />}
      <div className="g2 fl-mt">
        <Panel
          title={t('m01.w.pay.title')}
          items={[]}
          after={
            <div className="bd">
              <table className="t" aria-label={t('m01.w.pay.title')}>
                <thead>
                  <tr>
                    <th>
                      {t('m01.status')}
                      <Src s="ENT" />
                    </th>
                    {['computation', 'disbursement', 'release'].map((c) => (
                      <th key={c}>
                        {t(`m01.w.pay.${c}`)}
                        <Src s="RULE" />
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {['stop', 'withheld', 'absconding'].map((k) => (
                    <tr key={k}>
                      {['name', 'computation', 'disbursement', 'release'].map((c) => (
                        <td key={c}>{t(`m01.w.pay.${k}.${c}`)}</td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          }
        />
        <Panel title={t('m01.w.rules.title')} items={ruleItems(t)} />
      </div>
      <RecordOrders eis={first?.eis ?? null} mayAttach={mayEdit} />
      {opened && <RecordDialog mode={opened} onClose={() => setOpened(null)} />}
    </div>
  );
}

function RecordDialog({ mode, onClose }: { mode: Mode; onClose: () => void }) {
  const { t } = useI18n();
  const dialog = useDialogFocus<HTMLDivElement>();
  const title = t('wf.viewHead', [t('screen.M-01')]);
  return (
    <div
      className="fl-modal-backdrop"
      role="presentation"
      onKeyDown={(e) => {
        if (e.key === 'Escape') onClose();
      }}
    >
      <div ref={dialog} className="fl-modal fl-modal-wide" role="dialog" aria-modal="true" aria-label={title}>
        <EmployeeDossier eis={mode.eis} onClose={onClose} />
        <OwnUnitPanels key={mode.eis} eis={mode.eis} />
      </div>
    </div>
  );
}

/** The record the first page opens on, as the pack draws EIS 104882: its detail and its histories. */
function DrawnRecord({ eis, mayEdit, onEdit }: { eis: string; mayEdit: boolean; onEdit: () => void }) {
  const { t } = useI18n();
  const detail = useEmployee(eis);
  const d = detail.data;
  const label = d ? `${d.firstName} ${d.lastName ?? ''}`.trim() : '';
  return (
    <>
      <div className="g2 fl-mt">
        <RecordDetail eis={eis} d={d} label={label} mayEdit={mayEdit} onEdit={onEdit} />
        <div>
          <Panel title={t('m01.w.val.title')} items={validationItems(t)} />
          <Panel
            title={t('m01.w.join.title')}
            items={joinerItems(t)}
            after={
              <div className="bd">
                <div className="oq">{t('m01.w.join.note')}</div>
              </div>
            }
          />
        </div>
      </div>
      <div className="g2 fl-mt">
        <Tbl
          title={t('m01.w.memberIds', [eis, label])}
          cols={[
            { t: t('m01.w.mid.id'), s: 'HRMS' },
            { t: t('m01.w.mid.category'), s: 'HRMS' },
            { t: t('m01.w.mid.zone'), s: 'HRMS' },
            { t: t('m01.w.mid.from'), s: 'HRMS' },
            { t: t('m01.w.mid.to'), s: 'HRMS' },
            { t: t('m01.status'), s: 'HRMS' },
          ]}
          after={
            <div className="bd">
              <div className="oq">{t('m01.w.mid.note')}</div>
            </div>
          }
        >
          <ServerPagination
            queryKey={registerKeys.memberIds(eis)}
            fetchPage={registerPages.memberIds(eis)}
            empty={<Empty text={t('m01.w.mid.none')} />}
          >
            {(rows) =>
              rows.map((r) => (
                <tr key={`${r.idType}-${r.memberId}-${r.effectiveFrom}`}>
                  <td>{r.memberId ?? '—'}</td>
                  <td>{r.category ?? '—'}</td>
                  <td>{r.zone ?? '—'}</td>
                  <td>{date(r.effectiveFrom)}</td>
                  <td>{r.effectiveTo ? date(r.effectiveTo) : '—'}</td>
                  <td>
                    {t(`m01.w.mid.status.${r.status}`)}
                    {r.reason ? <div className="sub2">{r.reason}</div> : null}
                  </td>
                </tr>
              ))
            }
          </ServerPagination>
        </Tbl>
        <Panel
          title={t('m01.w.retain.title')}
          items={[
            {
              icon: '🏦',
              title: t('m01.w.retain.item'),
              src: 'RULE',
              text: t('m01.w.retain.text'),
              tone: 'ok',
            },
          ]}
          after={
            <div className="bd">
              <div className="oq">{t('m01.w.retain.note')}</div>
            </div>
          }
        />
      </div>
      <div className="g2 fl-mt">
        <Tbl
          title={t('m01.w.staff', [d?.panMasked ?? '—', label])}
          cols={[
            { t: t('m01.w.st.old'), s: 'SYS' },
            { t: t('m01.w.st.new'), s: 'SYS' },
            { t: t('m01.w.st.origin'), s: 'ENT' },
            { t: t('m01.w.st.destination'), s: 'ENT' },
            { t: t('m01.w.st.effective'), s: 'ENT' },
            { t: t('m01.status'), s: 'SYS' },
          ]}
          after={
            <div className="bd">
              <div className="oq">{t('m01.w.st.note')}</div>
            </div>
          }
        >
          <ServerPagination
            queryKey={registerKeys.staffNumbers(eis)}
            fetchPage={registerPages.staffNumbers(eis)}
            empty={<Empty text={t('m01.none')} />}
          >
            {(rows) =>
              rows.map((r) => (
                <tr key={`${r.oldNumber}-${r.newNumber}`}>
                  <td>{r.oldNumber ?? '—'}</td>
                  <td>{r.newNumber}</td>
                  <td>{r.origin ?? '—'}</td>
                  <td>{r.destination ?? '—'}</td>
                  <td>{r.effectiveDate ? date(r.effectiveDate) : '—'}</td>
                  <td>{t(`m01.w.st.event.${r.event}`)}</td>
                </tr>
              ))
            }
          </ServerPagination>
        </Tbl>
        <Panel
          title={t('m01.w.anchor.title')}
          items={[
            { icon: '🪪', title: t('m01.w.anchor.1'), src: 'HRMS', text: t('m01.w.anchor.1m'), tone: 'info' },
            { icon: '🔀', title: t('m01.w.anchor.2'), src: 'RULE', text: t('m01.w.anchor.2m'), tone: 'pur' },
          ]}
        />
      </div>
    </>
  );
}

function RecordDetail({
  eis,
  d,
  label,
  mayEdit,
  onEdit,
}: {
  eis: string;
  d: Detail | undefined;
  label: string;
  mayEdit: boolean;
  onEdit: () => void;
}) {
  const { t } = useI18n();
  const me = useMe();
  const extras = useRecordExtras(eis).data;
  const v = (value?: string | null) => (value && value.trim() !== '' ? value : '—');
  const day = (value?: string | null) => (value ? date(value) : '—');
  const flag = (value?: boolean | null) => (value ? '✓' : '✗');
  const money = (value?: number | null) => (value === undefined || value === null ? '—' : amount(value));
  // A-39 / BR-SEC-002: drawn for the two roles the pack names; the decryption function is PENDING MASK-1.
  const mayUnmask = can(me.data, 'M-01', 'FLAG') || can(me.data, 'X-03', 'EDIT');
  const rows: KvRow[] = [
    { k: t('m01.w.d.identity'), v: '', section: true },
    { k: 'EMP_NUM', v: eis, s: 'HRMS' },
    {
      k: t('m01.w.d.panDob'),
      v: (
        <>
          {v(d?.panMasked)} <span className="sub2">{t('m01.w.d.panNote')}</span> · {day(d?.birthDate)}{' '}
          <span className="sub2">{t('m01.w.d.dobNote')}</span>
        </>
      ),
      s: 'HRMS',
    },
    {
      k: t('m01.w.d.joinRetire'),
      v: `${day(d?.joiningDate)} · ${day(d?.retirementDate)}`,
      s: 'HRMS',
    },
    { k: t('m01.w.d.cadre'), v: `${v(extras?.cadre)} · ${v(d?.designation)}`, s: 'HRMS' },
    { k: t('m01.w.d.determinants'), v: '', section: true },
    {
      k: t('m01.w.d.pattern'),
      v: (
        <>
          {v(d?.payPattern)} <span className="sub2">{t('m01.w.d.patternNote')}</span>
        </>
      ),
      s: 'ENT',
    },
    {
      k: 'PF_TYPE',
      v: (
        <>
          {v(d?.providentFundType)} <span className="sub2">{t('m01.w.d.pfNote')}</span>
        </>
      ),
      s: 'RULE',
    },
    { k: 'PAY_SCALE_CODE', v: v(extras?.payScaleCode), s: 'HRMS' },
    {
      k: 'BASIC',
      v: (
        <>
          {money(d?.currentBasic)} <span className="sub2">{t('m01.w.d.basicNote')}</span>{' '}
          <span className="sub2" data-testid="m01-hrms-read">
            {extras?.hrmsAvailable
              ? t('m01.w.d.hrmsNow', [money(extras.hrmsBasic), v(extras.hrmsStatus)])
              : t('m01.w.d.hrmsDown')}
          </span>
        </>
      ),
      s: 'HRMS',
    },
    { k: 'OLD_BASIC', v: money(extras?.oldBasic), s: 'SYS' },
    {
      k: t('m01.w.d.daRate'),
      v: d?.daRateApplied === undefined || d?.daRateApplied === null ? '—' : `${d.daRateApplied}%`,
      s: 'RULE',
    },
    { k: t('m01.w.d.hraGroup'), v: '', section: true },
    {
      k: 'CITY_TYPE_HRA',
      v: (
        <>
          {v(d?.hraCityClass)} <span className="sub2">{t('m01.w.d.cityNote')}</span>
        </>
      ),
      s: 'HRMS',
    },
    {
      k: t('m01.w.d.hraRate'),
      v: d?.hraRateApplied === undefined || d?.hraRateApplied === null ? '—' : `${d.hraRateApplied}%`,
      s: 'RULE',
    },
    { k: 'ACCOM_TYPE / LEASE_AMT', v: '—', s: 'ENT' },
    { k: t('m01.w.d.hraPayable'), v: '—', s: 'CALC' },
    { k: t('m01.w.d.statutory'), v: '', section: true },
    {
      k: 'CPF_CODE',
      v: (
        <>
          {v(d?.cpfCode)} <span className="sub2">{t('m01.w.d.cpfNote')}</span>
        </>
      ),
      s: 'HRMS',
    },
    { k: 'VCPF_SUBS_AMOUNT', v: '—', s: 'ESS' },
    { k: 'FPS_NUM / PRAN', v: `— · ${v(d?.pranNumber)}`, s: 'HRMS' },
    { k: 'BANK_CODE / AC_NO', v: `${v(extras?.bankCode)} · ${v(d?.bankAccountMasked)}`, s: 'HRMS' },
    { k: t('m01.w.d.cpfExt'), v: '', section: true },
    { k: t('m01.w.d.eps'), v: v(d?.epsNumber), s: 'HRMS' },
    { k: t('m01.w.d.epsDoj'), v: day(d?.epsJoinDate), s: 'HRMS' },
    { k: t('m01.w.d.uan'), v: t(d?.uanOnRecord ? 'm01.w.d.onRecord' : 'm01.w.d.notOnRecord'), s: 'HRMS' },
    { k: t('m01.w.d.control'), v: '', section: true },
    {
      k: t('m01.w.d.hold'),
      v: `${flag(d?.holdFlag)} · ${flag(d?.stopPaymentFlag)} · ${flag(d?.abscondingFlag)}`,
      s: 'ENT',
    },
    {
      k: t('m01.w.d.casual'),
      v: (
        <>
          {t(d?.casualLabour ? 'common.yes' : 'common.no')}{' '}
          <span className="sub2">{t('m01.w.d.casualNote')}</span>
        </>
      ),
      s: 'HRMS',
    },
    {
      k: t('m01.w.d.aadhaar'),
      v: (
        <>
          {v(d?.aadhaarMasked)} <span className="sub2">{t('m01.w.d.aadhaarNote')}</span>{' '}
          {mayUnmask ? (
            <Explained label={t('m01.w.unmask')} why={t('m01.w.why.unmaskPending')} kind="sm" />
          ) : (
            <Locked label={t('m01.w.unmask')} why={t('m01.w.why.unmask')} kind="sm" />
          )}
        </>
      ),
      s: 'HRMS',
    },
    {
      k: t('m01.w.d.migration'),
      v: (
        <span className="pill p-info">
          {extras?.recordOrigin === 'MIGRATED' && extras.legacyEmpNum
            ? t('m01.w.d.legacy', [extras.legacyEmpNum])
            : t('m01.w.d.interface')}
        </span>
      ),
      s: 'SYS',
    },
  ];
  return (
    <KvCard
      title={t('m01.w.detail', [eis, label])}
      act={mayEdit ? <Act label={t('m01.w.edit')} kind="sm" onClick={onEdit} /> : undefined}
      rows={rows}
      after={<div className="oq">{t('m01.w.d.note')}</div>}
    />
  );
}

/** The order card of M-01 (SRS-G-30): the correction or salary release orders of the opened record. */
function RecordOrders({ eis, mayAttach }: { eis: string | null; mayAttach: boolean }) {
  const { t } = useI18n();
  const stored = useEmployeeOrders(eis);
  const types = useDocumentTypes(true);
  const attach = useAttachEmployeeOrder(eis);
  return (
    <OrderCopyCard
      key={eis ?? ''}
      hint={t('m01.w.ordHint')}
      canAttach={mayAttach && eis !== null}
      readOnly={!mayAttach}
      stored={stored.data}
      storedError={stored.error ?? types.error}
      fileHref={(fileId) => registerUrls.order(eis ?? '', fileId)}
      today={todayIso()}
      attachmentRequired={false}
      referenceRequired={(code) => types.data?.find((d) => d.code === code)?.referenceRequired ?? false}
      onAttach={async (o) => {
        for (const file of o.files) {
          await attach.mutateAsync({
            documentType: o.documentType,
            reference: o.reference,
            orderDate: o.orderDate,
            issuingAuthority: o.issuingOffice,
            file,
          });
        }
      }}
    />
  );
}

function items(
  t: (key: string, args?: (string | number)[]) => string,
  prefix: string,
  drawn: [string, Source, string][],
): PanelItem[] {
  return drawn.map(([icon, src, tone], i) => ({
    icon,
    src,
    tone: tone as PanelItem['tone'],
    title: t(`${prefix}.${i + 1}`),
    text: t(`${prefix}.${i + 1}m`),
  }));
}

function validationItems(t: (key: string, args?: (string | number)[]) => string): PanelItem[] {
  return items(t, 'm01.w.val', [
    ['🆔', 'RULE', 'bad'],
    ['📅', 'RULE', 'warn'],
    ['🔒', 'HRMS', 'pur'],
    ['🏁', 'RULE', 'gray'],
    ['🔗', 'SYS', 'info'],
    ['🧬', 'RULE', 'ok'],
    ['🏛', 'RULE', 'info'],
  ]);
}

function joinerItems(t: (key: string, args?: (string | number)[]) => string): PanelItem[] {
  return items(t, 'm01.w.join', [
    ['1️⃣', 'INTF', 'info'],
    ['2️⃣', 'SYS', 'info'],
    ['3️⃣', 'ENT', 'ok'],
    ['4️⃣', 'SYS', 'warn'],
    ['5️⃣', 'SYS', 'ok'],
  ]);
}

function ruleItems(t: (key: string, args?: (string | number)[]) => string): PanelItem[] {
  return items(t, 'm01.w.rules', [
    ['🏠', 'RULE', 'warn'],
    ['⅓', 'RULE', 'warn'],
    ['🍴', 'CALC', 'info'],
    ['🗑', 'SYS', 'info'],
  ]);
}

/**
 * The Payroll-owned flags of a member of the session's own posting. SRS-G-53: a previous unit reads
 * the record only (no flag panel), and nothing shows before the record has loaded.
 */
function OwnUnitPanels({ eis }: { eis: string }) {
  const detail = useEmployee(eis);
  if (!detail.data || detail.data.transferredOut) {
    return null;
  }
  return (
    <>
      <AllowanceFlags eis={eis} />
      <ControlFlags eis={eis} />
    </>
  );
}

function EmployeeDossier({ eis, onClose }: { eis: string; onClose: () => void }) {
  const { t } = useI18n();
  const reference = useReference();
  const detail = useEmployee(eis);

  const d = detail.data;
  return (
    <div className="card">
      <div className="hd">
        <h3>
          {d
            ? t('m01.dossier', [`${d.firstName} ${d.lastName ?? ''}`.trim(), d.eis])
            : t('m01.dossierLoading', [eis])}
        </h3>
        <span className="sp" />
        <button type="button" className="btn sm" onClick={onClose}>
          {t('common.close')}
        </button>
      </div>
      <div className="bd">
        <Problem error={detail.error} />
        {d?.transferredOut && (
          <p className="fl-hint" role="note" data-testid="m01-previous-unit">
            {t('m01.previousUnit', [date(d.transferredOut)])}
          </p>
        )}
        {d && (
          <div className="kv">
            <span className="k">{t('m01.eis')}</span>
            <span>{d.eis}</span>
            <span className="k">{t('m01.status')}</span>
            <span>
              <StatusPill status={d.status} />
            </span>
            <span className="k">{t('m01.employmentStatus')}</span>
            <span>{reference.label('EMPLOYMENT_STATUS', d.employmentStatus)}</span>
            <span className="k">{t('m01.designation')}</span>
            <span>{d.designation ?? '—'}</span>
            <span className="k">{t('m01.band')}</span>
            <span>{d.band ?? '—'}</span>
            <span className="k">{t('m01.location')}</span>
            <span>{d.location ?? '—'}</span>
            <span className="k">{t('m01.payPattern')}</span>
            <span>{d.payPattern ?? '—'}</span>
            <span className="k">{t('m01.employeeType')}</span>
            <span>{d.employeeType}</span>
            <span className="k">{t('m01.basic')}</span>
            <span>{amount(d.currentBasic)}</span>
            <span className="k">{t('m01.nextIncrementDue')}</span>
            <span>{d.nextIncrementDue ? date(d.nextIncrementDue) : '—'}</span>
            <span className="k">{t('m01.pan')}</span>
            <span>{d.panMasked ?? '—'}</span>
            <span className="k">{t('m01.cpf')}</span>
            <span>{d.cpfCode ?? '—'}</span>
            <span className="k">{t('field.mobileNumber')}</span>
            <span>{d.mobileNumber ?? '—'}</span>
            <span className="k">{t('field.emailAddress')}</span>
            <span>{d.emailAddress ?? '—'}</span>
            <span className="k">{t('field.bankAccount')}</span>
            <span>{d.bankAccountMasked ?? '—'}</span>
          </div>
        )}
        {d && <DossierGroups d={d} />}
        {d?.changePending && <p className="fl-hint">{t('m01.pendingNote')}</p>}
        {d?.status === 'PAYROLL_SETUP_PENDING' && (
          <OnboardingSubmission eis={d.eis} cda={d.payPattern === 'CDA'} />
        )}
      </div>
    </div>
  );
}

const PRINTABLE = /^[\x20-\x7E\t\r\n]+$/;

type DossierDetail = NonNullable<ReturnType<typeof useEmployee>['data']>;

/**
 * SRS-1-52 / SRS-1-54 / SRS-1-76 (US-GAP-40, US-16-04 AC4): the further groups of the M-01 field
 * table, each value with its source; all read-only here.
 */
function DossierGroups({ d }: { d: DossierDetail }) {
  const { t } = useI18n();
  const day = (v?: string | null) => (v ? date(v) : '—');
  const text = (v?: string | null) => (v && v.trim() !== '' ? v : '—');
  const yesNo = (v?: boolean | null) =>
    v === undefined || v === null ? '—' : t(v ? 'common.yes' : 'common.no');
  const pct = (v?: number | null) => (v === undefined || v === null ? '—' : `${v} %`);
  const row = (label: string, source: Source, value: string) => (
    <>
      <span className="k">
        {t(label)}
        <Src s={source} />
      </span>
      <span>{value}</span>
    </>
  );
  return (
    <>
      <h4>{t('m01.group.service')}</h4>
      <div className="kv" aria-label={t('m01.group.service')}>
        {row('m01.birthDate', 'HRMS', day(d.birthDate))}
        {row('m01.joiningDate', 'HRMS', day(d.joiningDate))}
        {row('m01.retirementDate', 'HRMS', day(d.retirementDate))}
        {row('m01.gender', 'HRMS', text(d.gender))}
        {row('m01.employeeCategory', 'HRMS', text(d.employeeCategory))}
        {row('m01.cdaStaffCode', 'ENT', text(d.cdaStaffCode))}
      </div>
      <h4>{t('m01.group.pay')}</h4>
      <div className="kv" aria-label={t('m01.group.pay')}>
        {row('m01.pfType', 'CALC', text(d.providentFundType))}
        {row('m01.daRateApplied', 'RULE', pct(d.daRateApplied))}
        {row('m01.hraCityClass', 'CALC', text(d.hraCityClass))}
        {row('m01.hraRateApplied', 'RULE', pct(d.hraRateApplied))}
      </div>
      <h4>{t('m01.group.statutory')}</h4>
      <div className="kv" aria-label={t('m01.group.statutory')}>
        {row('m01.aadhaar', 'HRMS', text(d.aadhaarMasked))}
        {row('m01.pran', 'HRMS', text(d.pranNumber))}
        {row('m01.uanOnRecord', 'HRMS', yesNo(d.uanOnRecord))}
        {row('m01.epsNumber', 'HRMS', text(d.epsNumber))}
        {row('m01.epsJoinDate', 'HRMS', day(d.epsJoinDate))}
        {row('m01.epfoMemberId', 'HRMS', text(d.epfoMemberId))}
        {row('m01.epsPre2014', 'HRMS', yesNo(d.epsPre2014Member))}
        {row('m01.epsEntitlementDate', 'HRMS', day(d.epsEntitlementDate))}
        {row(
          'm01.epsSeparation',
          'HRMS',
          d.epsSeparationReason ? `${d.epsSeparationReason} ${day(d.epsSeparationDate)}` : '—',
        )}
      </div>
      <h4>{t('m01.group.control')}</h4>
      <div className="kv" aria-label={t('m01.group.control')}>
        {row('m01.holdFlag', 'ENT', yesNo(d.holdFlag))}
        {row('m01.stopPaymentFlag', 'ENT', yesNo(d.stopPaymentFlag))}
        {row('m01.abscondingFlag', 'HRMS', yesNo(d.abscondingFlag))}
        {row('m01.casualLabour', 'HRMS', yesNo(d.casualLabour))}
        {row(
          'm01.payStatus',
          'SYS',
          d.payStatus ? `${d.payStatus} - ${t(`m01.payStatus.${d.payStatus}`)}` : '—',
        )}
      </div>
    </>
  );
}

/** SRS-1-11: the onboarding submission of a record in Payroll Setup Pending (Establishment / HR). */
function OnboardingSubmission({ eis, cda }: { eis: string; cda: boolean }) {
  const { t } = useI18n();
  const me = useMe();
  const toast = useToast();
  const submit = useSubmitOnboarding(eis);
  const [code, setCode] = useState('');
  const [reason, setReason] = useState('');
  const mayOnboard = can(me.data, 'M-01', 'SUBMIT');
  const why = !mayOnboard
    ? 'reason.hrOnly'
    : cda && !/^[\x21-\x7E]{1,20}$/.test(code.trim())
      ? 'm01.why.cdaStaffCode'
      : !reason.trim() || !PRINTABLE.test(reason.trim())
        ? 'm01.why.onboardReason'
        : null;

  async function send() {
    try {
      await submit.mutateAsync({ cdaStaffCode: cda ? code.trim() : null, reason: reason.trim() });
      toast(t('m01.onboardSent'));
    } catch {
      // shown below from the mutation's error
    }
  }

  return (
    <div className="form-grid">
      <p className="fl-hint">{t('m01.setupPendingNote')}</p>
      {cda && (
        <label>
          {t('m01.cdaStaffCode')}
          <Src s="ENT" />
          <input maxLength={20} value={code} onChange={(e) => setCode(e.target.value)} />
        </label>
      )}
      <label>
        {t('m01.onboardReason')}
        <Src s="ENT" />
        <TextAreaField maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />
      </label>
      <div className="toolbar">
        <button
          type="button"
          className="btn primary"
          disabled={why !== null || submit.isPending}
          title={why ? t(why) : undefined}
          onClick={() => void send()}
        >
          {t('action.m01.onboard')}
        </button>
      </div>
      <Problem error={submit.error} />
    </div>
  );
}
