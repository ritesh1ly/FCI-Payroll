'use client';

import { useMemo, useState } from 'react';
import { HistoryButton } from '@/modules/audit';
import { RegisterSection, useApproveRow } from '@/shared/ui/masterParts';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { businessToday } from '@/shared/time';
import { date, ExportButton, Problem, ServerPagination, useToast, withBase } from '@/shared/ui';
import { OrderCopyCard } from '@/shared/ui/orderCopy';
import {
  Act,
  Explained,
  KvCard,
  Locked,
  Panel,
  RowActs,
  Tbl,
  Toolbar,
  ViewDialog,
} from '@/shared/ui/wireframe';
import type { ScreenFilter, MemberLine } from '../api/labourScreenApi';
import { figure, memberPill } from '../business/labourScreenRules';
import { useEngine } from '../orchestration/captureQueries';
import { useGangSearch, useOptions } from '../orchestration/labourQueries';
import {
  attachmentsPage,
  memberLinesPage,
  membersExportUrl,
  orderUrl,
  screenKeys,
  useAttachMemberOrder,
  useDocumentTypes,
  useFirstMember,
  useLightDuty,
  useMemberOrders,
} from '../orchestration/screenQueries';
import { GangFilter, TypeSelect } from './CaptureParts';
import { LabourMaster as LabourRegister } from './LabourMaster';

const PILL: Record<string, string> = {
  PENDING_APPROVAL: 'p-warn',
  APPROVED: 'p-ok',
  REJECTED: 'p-bad',
  ENDED: 'p-gray',
  CANCELLED: 'p-gray',
};

/**
 * M-02 Labour Master as Wireframes v6.1 draws it (OD-121; SRS-2-27, SRS-2-30, SRS-2-47, SRS-2-49; plan 20): one
 * view per engine (the sidebar's Departmental, DPS and NWNP entries, or the drawn selector), the depot and gang
 * filters, the master table, Transfer Controls, the engine behaviour, the Permanent Light Duty order, the temporary
 * attachments and the order card. Labour are not in HRMS: this data is entered in Payroll by Establishment / HR and
 * the Payroll Checker approves it. The entry, amendment, separation, transfer, correction, reinstatement and
 * nominee functions stay in the closed register below; Edit and the drawn buttons open it.
 */
export function LabourMaster() {
  const { t } = useI18n();
  const me = useMe();
  const toast = useToast();
  const approve = useApproveRow();
  const { type, chosen, choose } = useEngine();
  const options = useOptions();
  const [depot, setDepot] = useState<number | null>(null);
  const [gang, setGang] = useState<string | null>(null);
  const [q, setQ] = useState('');
  const [register, setRegister] = useState(false);
  const [opening, setOpening] = useState<MemberLine | null>(null);
  const [viewing, setViewing] = useState<MemberLine | null>(null);
  const [picked, setPicked] = useState<MemberLine | null>(null);
  const mayEdit = can(me.data, 'M-02', 'EDIT');
  // The drawn ✔ is the Payroll Checker's. Establishment / HR also holds APPROVE for its HQ level of a name or
  // date-of-birth correction (V056_4), decided in W-01: the maker of the master draws no ✔ on a row.
  const mayApprove = can(me.data, 'M-02', 'APPROVE') && !mayEdit;
  const mayExport = can(me.data, 'M-02', 'EXPORT');
  const mayAttach = can(me.data, 'M-09', 'EDIT');
  const filter = useMemo<ScreenFilter>(
    () => ({ type, depot, gang: gang ?? undefined, q }),
    [type, depot, gang, q],
  );
  const gangs = useGangSearch({ payrollType: type, status: 'ACTIVE' });
  const gangOptions = (gangs.data?.items ?? []).map((g) => ({
    id: g.gangId,
    code: g.gangCode,
    name: g.gangName,
    type: g.gangType,
  }));
  const first = useFirstMember(filter);
  const record = picked ?? first.data?.items?.[0] ?? null;
  const lightDuty = useLightDuty(type);
  const order = lightDuty.data?.items?.[0] ?? null;
  const hrOnly = t('m02w.why.hrOnly');
  const a09 = t('m02w.why.a09');
  const typeName = t(`i01w.type.${type}`);
  const nwnp = type === 'NWNP_LABOUR';

  /** Edit Record, Transfer and Separation act on a member: the register holds each member's actions. */
  function toRegister() {
    setRegister(true);
    toast(t('m02w.pickMember'));
  }

  function edit(row: MemberLine) {
    setOpening(row);
    setRegister(true);
  }

  const active = (engine: string) =>
    engine === type ? <span className="pill p-ok">{t('m02w.engine.active')}</span> : undefined;

  const wage = (r: MemberLine) => (nwnp ? t(`m02w.mgw.${r.gangRole ?? 'NONE'}`) : figure(r.basicWage));

  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sp" />
        {record ? (
          <HistoryButton
            entity="labour_profile"
            recordKey={record.labourCode}
            label={`${record.labourCode} · ${record.name}`}
            text={t('lw.history')}
            className="btn"
          />
        ) : (
          <Act label={t('lw.history')} onClick={() => toast(t('m02w.none'))} />
        )}
        {mayEdit ? (
          <Act label={t('m02w.editRecord')} kind="pri" onClick={toRegister} />
        ) : (
          <Locked label={t('m02w.editRecord')} kind="pri" why={hrOnly} />
        )}
      </div>
      <Toolbar
        search={q}
        onSearch={setQ}
        exportControl={
          mayExport ? (
            <ExportButton href={membersExportUrl(filter)} what={t('screen.M-02')} label={t('wf.export')} />
          ) : (
            <Explained label={t('wf.export')} why={t('lw.why.export')} />
          )
        }
      >
        <TypeSelect
          value={type}
          onChange={(next) => {
            choose(next);
            setGang(null);
            setPicked(null);
          }}
        />
        <select
          aria-label={t('lw.depotFilter')}
          value={depot ?? ''}
          onChange={(e) => setDepot(e.target.value ? Number(e.target.value) : null)}
        >
          <option value="">{t('m09.allDepots')}</option>
          {(options.data?.depots ?? []).map((d) => (
            <option key={d.id} value={d.id}>
              {d.name}
            </option>
          ))}
        </select>
        <GangFilter
          gangs={gangOptions}
          value={gangOptions.find((g) => g.code === gang)?.id ?? null}
          onChange={(id) => setGang(gangOptions.find((g) => g.id === id)?.code ?? null)}
        />
      </Toolbar>
      <Tbl
        title={t('m02w.title', [typeName])}
        label={t('m02w.tableLabel')}
        acts={
          mayEdit ? (
            <>
              <Act label={t('m02w.transfer')} kind="sm" onClick={toRegister} />
              <Act label={t('m02w.separation')} kind="sm" onClick={toRegister} />
            </>
          ) : (
            <>
              <Locked label={t('m02w.transfer')} kind="sm" why={a09} />
              <Locked label={t('m02w.separation')} kind="sm" why={a09} />
            </>
          )
        }
        cols={[
          { t: t('m02w.col.code'), s: 'HRMS' },
          { t: t('m02w.col.name'), s: 'HRMS' },
          { t: t('m02w.col.designation'), s: 'ENT' },
          { t: t('m02w.col.gang'), s: 'ENT' },
          { t: t('m02w.col.depot'), s: 'HRMS' },
          { t: t('m02w.col.aadhaar'), s: 'HRMS' },
          nwnp
            ? { t: t('m02w.col.mgw'), s: 'RULE', num: true }
            : { t: t('m02w.col.basic'), s: 'ENT', num: true },
          { t: t('m02w.col.cpf'), s: 'ENT' },
          { t: t('m02w.col.status'), s: 'SYS' },
          { t: t('m02w.col.actions'), s: 'SYS' },
        ]}
      >
        <ServerPagination
          queryKey={screenKeys.of('members', filter)}
          fetchPage={memberLinesPage(filter)}
          empty={t('m02w.none')}
        >
          {(rows) =>
            rows.map((r) => {
              const pill = memberPill(r);
              return (
                <tr key={r.employeeId}>
                  <td>
                    <b>{r.labourCode}</b>
                  </td>
                  <td>{r.name}</td>
                  <td>{r.designation ?? '—'}</td>
                  <td>{r.gang ?? '—'}</td>
                  <td>{r.depot ?? '—'}</td>
                  <td>{r.aadhaarMasked ?? '—'}</td>
                  <td className="num">{wage(r)}</td>
                  <td>{r.cpfCode ?? '—'}</td>
                  <td>
                    <span className={`pill ${pill.tone}`}>{t(pill.key)}</span>
                  </td>
                  <td>
                    <RowActs
                      viewName={`${t('common.view')} ${r.labourCode}`}
                      editName={`${t('common.edit')} ${r.labourCode}`}
                      onView={() => {
                        setPicked(r);
                        setViewing(r);
                      }}
                      onEdit={mayEdit ? () => edit(r) : undefined}
                      editWhy={t('m02w.why.edit')}
                      onApprove={mayApprove ? () => approve(r.transferPending) : undefined}
                      cancel
                      onCancel={mayEdit ? () => toast(t('m02w.why.cancel')) : undefined}
                    />
                  </td>
                </tr>
              );
            })
          }
        </ServerPagination>
      </Tbl>
      <div className="g2 fl-mt">
        <KvCard
          title={t('m02w.transferControls')}
          rows={[
            { k: t('m02w.tc.stage'), v: t('m02w.tc.stageV'), s: 'RULE' },
            { k: t('m02w.tc.attendance'), v: t('m02w.tc.attendanceV'), s: 'CALC' },
            { k: t('m02w.tc.slips'), v: t('m02w.tc.slipsV'), s: 'RULE' },
            { k: t('m02w.tc.gang'), v: t('m02w.tc.gangV'), s: 'SYS' },
            { k: t('m02w.tc.salary'), v: t('m02w.tc.salaryV'), s: 'CALC' },
            { k: t('m02w.tc.incentive'), v: t('m02w.tc.incentiveV'), s: 'CALC' },
            {
              k: t('m02w.tc.before'),
              v: type === 'DEPT_LABOUR' ? t('m02w.tc.required') : <b>{t('m02w.tc.mandatory')}</b>,
              s: 'RULE',
            },
          ]}
        />
        <Panel
          title={t('m02w.engine')}
          items={[
            {
              icon: '🏭',
              tone: 'info',
              title: t('m02w.engine.dept'),
              src: 'RULE',
              text: t('m02w.engine.deptM'),
              right: active('DEPT_LABOUR'),
            },
            {
              icon: '🚚',
              tone: 'info',
              title: t('m02w.engine.dps'),
              src: 'RULE',
              text: t('m02w.engine.dpsM'),
              right: active('DPS_LABOUR'),
            },
            {
              icon: '📉',
              tone: 'info',
              title: t('m02w.engine.nwnp'),
              src: 'RULE',
              text: t('m02w.engine.nwnpM'),
              right: active('NWNP_LABOUR'),
            },
            {
              icon: '🚫',
              tone: 'bad',
              title: t('m02w.engine.past'),
              src: 'RULE',
              text: t('m02w.engine.pastM'),
              right: <span className="pill p-ok">{t('m02w.engine.on')}</span>,
            },
          ]}
        />
      </div>
      <div className="g2 fl-mt">
        <KvCard
          title={t('m02w.lightDuty')}
          rows={[
            { k: t('m02w.ld.labour'), v: order ? `${order.labourCode} ${order.labour}` : '—', s: 'SYS' },
            {
              k: t('m02w.ld.change'),
              v: order ? `${order.fromDesignation} → ${order.toDesignation}` : '—',
              s: 'ENT',
            },
            { k: t('m02w.ld.reason'), v: order ? t('m02w.ld.reasonV') : '—', s: 'ENT' },
            { k: t('m02w.ld.effective'), v: order ? date(order.effectiveDate) : '—', s: 'ENT' },
            { k: t('m02w.ld.order'), v: order?.orderReference ?? '—', s: 'ENT' },
            {
              k: t('m02w.ld.pay'),
              v: order ? <b>{t(`m02.payOption.${order.payOption}`)}</b> : '—',
              s: 'ENT',
            },
            { k: t('m02w.ld.from'), v: t('m02w.ld.fromV'), s: 'SYS' },
            { k: t('m02w.ld.back'), v: t('m02w.ld.backV'), s: 'CALC' },
            { k: t('m02w.ld.mc'), v: t('m02w.ld.mcV'), s: 'SYS' },
          ]}
        />
        <Tbl
          title={t('m02w.attach')}
          acts={
            mayAttach ? (
              <Act label={t('m02w.newAttachment')} kind="sm" href={withBase('/payroll/?s=M-09')} />
            ) : (
              <Explained label={t('m02w.newAttachment')} kind="sm" why={t('m02w.why.attach')} />
            )
          }
          cols={[
            { t: t('m02w.att.ref'), s: 'SYS' },
            { t: t('m02w.att.labour'), s: 'SYS' },
            { t: t('m02w.att.category'), s: 'ENT' },
            { t: t('m02w.att.gang'), s: 'ENT' },
            { t: t('m02w.att.from'), s: 'ENT' },
            { t: t('m02w.att.to'), s: 'ENT' },
            { t: t('m02w.att.status'), s: 'SYS' },
          ]}
          after={
            <div className="bd">
              <div className="oq">{t('m02w.notHrms')}</div>
            </div>
          }
        >
          <ServerPagination
            queryKey={screenKeys.of('attachments', type)}
            fetchPage={attachmentsPage(type)}
            empty={t('m02w.noAttachments')}
          >
            {(rows) =>
              rows.map((a) => (
                <tr key={a.id}>
                  <td>{a.reference}</td>
                  <td>
                    {a.labourCode} {a.labour}
                  </td>
                  <td>{t(`m02w.cat.${a.category}`)}</td>
                  <td>{a.gang}</td>
                  <td>{date(a.from)}</td>
                  <td>{a.to ? date(a.to) : t('m02w.att.permanent')}</td>
                  <td>
                    <span className={`pill ${PILL[a.status] ?? 'p-gray'}`}>
                      {t(`m02w.att.state.${a.status}`)}
                    </span>
                  </td>
                </tr>
              ))
            }
          </ServerPagination>
        </Tbl>
      </div>
      <MemberOrders key={record?.employeeId ?? 0} record={record} mayEdit={mayEdit} />
      {viewing && (
        <ViewDialog
          screen={t('screen.M-02')}
          onClose={() => setViewing(null)}
          rows={[
            { k: t('m02w.col.code'), v: viewing.labourCode, s: 'HRMS' },
            { k: t('m02w.col.name'), v: viewing.name, s: 'HRMS' },
            { k: t('m02w.col.designation'), v: viewing.designation ?? '—', s: 'ENT' },
            { k: t('m02w.col.gang'), v: viewing.gang ?? '—', s: 'ENT' },
            { k: t('m02w.col.depot'), v: viewing.depot ?? '—', s: 'HRMS' },
            { k: t('m02w.col.aadhaar'), v: viewing.aadhaarMasked ?? '—', s: 'HRMS' },
            nwnp
              ? { k: t('m02w.col.mgw'), v: wage(viewing), s: 'RULE' }
              : { k: t('m02w.col.basic'), v: wage(viewing), s: 'ENT' },
            { k: t('m02w.col.cpf'), v: viewing.cpfCode ?? '—', s: 'ENT' },
            { k: t('m02w.col.status'), v: t(memberPill(viewing).key), s: 'SYS' },
          ]}
        />
      )}
      <RegisterSection id="m02-register" label={t('m02w.register')} open={register} onToggle={setRegister}>
        {register && (
          <LabourRegister
            key={opening?.employeeId ?? 0}
            opening={opening}
            payrollType={chosen ?? undefined}
          />
        )}
      </RegisterSection>
      <Problem error={first.error ?? lightDuty.error} />
    </div>
  );
}

/** The drawn order card (SRS-G-30): the orders of the record viewed, the first of the list until one is. */
function MemberOrders({ record, mayEdit }: { record: MemberLine | null; mayEdit: boolean }) {
  const { t } = useI18n();
  const id = record?.employeeId ?? null;
  const stored = useMemberOrders(id);
  const types = useDocumentTypes();
  const attach = useAttachMemberOrder(id);
  return (
    <div className="fl-mt">
      <OrderCopyCard
        hint={t('m02w.ordHint')}
        canAttach={mayEdit && id !== null}
        readOnly={!mayEdit}
        stored={stored.data}
        storedError={stored.error ?? types.error}
        fileHref={(fileId) => orderUrl(id ?? 0, fileId)}
        today={businessToday()}
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
    </div>
  );
}
