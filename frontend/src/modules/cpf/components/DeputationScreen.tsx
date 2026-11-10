'use client';

import { useCallback, useState } from 'react';
import { todayIso, useDocumentTypes } from '@/modules/general';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { amount, date, Problem, ServerPagination } from '@/shared/ui';
import { RegisterSection } from '@/shared/ui/masterParts';
import { OrderCopyCard, type StoredOrder } from '@/shared/ui/orderCopy';
import {
  Act,
  Explained,
  Locked,
  Panel,
  RowActs,
  Tbl,
  Tiles,
  ViewDialog,
  WfTile,
} from '@/shared/ui/wireframe';
import type { DeputedRow } from '../api/cpfScreensApi';
import {
  deputationEvidenceUrl,
  useAddDeputationEvidence,
  useDeputedMember,
} from '../orchestration/cpfQueries';
import {
  cpfScreenKeys,
  cpfScreenPages,
  useCpfApprove,
  useDeputationBoard,
} from '../orchestration/cpfScreenQueries';
import { DeputationAdjustment as DeputationRegister } from './DeputationAdjustment';

const DASH = '—';
const NUM = new Intl.NumberFormat('en-IN');
const PILL: Record<string, string> = {
  PENDING_CHECKER: 'p-info',
  MAKER_SAVED: 'p-warn',
  APPROVED: 'p-ok',
  REJECTED: 'p-bad',
  AWAITING_ADVICE: 'p-gray',
};
const money = (v: number | null) => (v === null ? DASH : amount(v));

/**
 * I-09 CPF Adjustment / Recovery — Deputation as Wireframes v6.1 draws it (OD-121; SRS-6-27 … 6-30, BR-CPF-021 … 024):
 * the four tiles, one row per deputed member, the EPS split, why the transaction types are separate, the deputation
 * rules and the order copy of the chosen member. Every function of the old screen stays whole in the closed register:
 * the member lookup, the entry form, the borrowing organisations and the evidence of each entry. The right to record
 * is the Manager (CPF)'s, the decision the AGM (CPF)'s (V043_2): the drawn Payroll Maker and Checker are told so.
 */
export function DeputationAdjustment() {
  const { t } = useI18n();
  const me = useMe();
  const yymm = (me.data?.period ?? '').replace('-', '');
  const board = useDeputationBoard(yymm);
  const roles = me.data?.roles ?? [];
  const mayCreate = can(me.data, 'I-09', 'CREATE');
  const maker = roles.includes('PAYROLL_MAKER');
  const approve = useCpfApprove('I-09', 'i09w.why.agm');
  const [open, setOpen] = useState(false);
  const [viewing, setViewing] = useState<DeputedRow | null>(null);
  const [selected, setSelected] = useState<DeputedRow | null>(null);
  const b = board.data;
  const onRows = useCallback((rows: DeputedRow[]) => {
    // A member still awaiting the advice has no entry, so no evidence: the card opens on the first who has.
    setSelected(
      (prev) =>
        rows.find((r) => r.eis === prev?.eis) ??
        rows.find((r) => r.status !== 'AWAITING_ADVICE') ??
        rows[0] ??
        null,
    );
  }, []);
  const openRegister = () => setOpen(true);
  return (
    <>
      <div className="toolbar">
        <span className="sp" />
        {maker ? (
          <Explained label={t('i09w.act.import')} why={t('i09w.why.import')} />
        ) : (
          <Locked label={t('i09w.act.import')} why={t('i09w.why.importRole')} />
        )}
        {mayCreate && <Act label={t('i09w.act.record')} kind="pri" onClick={openRegister} />}
        {!mayCreate && maker && (
          <Explained label={t('i09w.act.record')} kind="pri" why={t('i09w.why.notManager')} />
        )}
        {!mayCreate && !maker && (
          <Locked label={t('i09w.act.record')} kind="pri" why={t('i09w.why.recordRole')} />
        )}
      </div>
      <Problem error={board.error} />
      <Tiles>
        <WfTile
          icon="🔁"
          label={t('i09w.tile.members')}
          src="HRMS"
          big={NUM.format(b?.members ?? 0)}
          sub={t('i09w.tile.membersSub', [b?.withAdvance ?? 0])}
        />
        <WfTile
          icon="➖"
          label={t('i09w.tile.recovery')}
          src="ESS"
          big={amount(b?.recovered ?? 0)}
          sub={t('i09w.tile.recoverySub', [b?.organisations ?? 0])}
        />
        <WfTile
          icon="🏦"
          label={t('i09w.tile.contribution')}
          src="ESS"
          big={amount(b?.contributions ?? 0)}
          sub={t('i09w.tile.contributionSub')}
        />
        <WfTile
          icon="✍️"
          label={t('i09w.tile.pending')}
          src="SYS"
          big={NUM.format(b?.pending ?? 0)}
          sub={t('i09w.tile.pendingSub')}
        />
      </Tiles>
      <Tbl
        title={t('i09w.table')}
        acts={<Act label={t('i09w.act.reconcile')} kind="sm" onClick={openRegister} />}
        cols={[
          { t: t('i09w.col.member'), s: 'HRMS' },
          { t: t('i09w.col.organisation'), s: 'ENT' },
          { t: t('i09w.col.period'), s: 'ENT' },
          { t: t('i09w.col.outstanding'), s: 'CALC', num: true },
          { t: t('i09w.col.instalment'), s: 'ESS', num: true },
          { t: t('i09w.col.balance'), s: 'CALC', num: true },
          { t: t('i09w.col.contribution'), s: 'ESS', num: true },
          { t: t('i09w.col.status'), s: 'SYS' },
          { t: t('i09w.col.actions'), s: 'SYS' },
        ]}
      >
        <ServerPagination
          queryKey={cpfScreenKeys.deputedMembers}
          fetchPage={cpfScreenPages.deputedMembers}
          empty={t('i09.none')}
          onRows={onRows}
        >
          {(rows) =>
            rows.map((r) => (
              <tr
                key={r.eis}
                aria-selected={selected?.eis === r.eis}
                className={selected?.eis === r.eis ? 'fl-row-selected' : undefined}
                onClick={() => setSelected(r)}
              >
                <td>
                  <b>{r.eis}</b> {r.name}
                </td>
                <td>{r.organisation ?? DASH}</td>
                <td>
                  {date(r.from)} → {r.to ? date(r.to) : t('i09.open')}
                </td>
                <td className="num">{money(r.outstanding)}</td>
                <td className="num">{money(r.instalment)}</td>
                <td className="num">{money(r.balanceAfter)}</td>
                <td className="num">
                  {r.contribution === null ? t('i09w.notRemitted') : amount(r.contribution)}
                </td>
                <td>
                  <span className={`pill ${PILL[r.status] ?? 'p-gray'}`}>{t(`i09w.st.${r.status}`)}</span>
                </td>
                <td>
                  <RowActs
                    onView={() => setViewing(r)}
                    onEdit={mayCreate ? openRegister : undefined}
                    editWhy={t('i09w.why.notManager')}
                    onApprove={approve ? () => approve(r.status === 'PENDING_CHECKER') : undefined}
                    viewName={t('i09w.viewName', [r.eis])}
                    editName={t('i09w.editName', [r.eis])}
                  />
                </td>
              </tr>
            ))
          }
        </ServerPagination>
      </Tbl>
      <div className="g2 fl-mt">
        <Tbl
          title={t('i09w.eps')}
          cols={[
            { t: t('i09w.eps.parameter'), s: 'RULE' },
            { t: t('i09w.eps.value'), s: 'RULE' },
            { t: t('i09w.eps.basis'), s: 'SYS' },
            { t: t('i09w.eps.level'), s: 'SYS' },
          ]}
        >
          {[1, 2, 3, 4].map((n) => (
            <tr key={n}>
              <td>{t(`i09w.eps.${n}.p`)}</td>
              <td>{t(`i09w.eps.${n}.v`)}</td>
              <td>{t(`i09w.eps.${n}.b`)}</td>
              <td>{t(`i09w.eps.${n}.l`)}</td>
            </tr>
          ))}
        </Tbl>
        <Panel
          title={t('i09w.why')}
          items={[
            { icon: '①', title: t('i09w.why.1.t'), text: t('i09w.why.1.m'), src: 'RULE', tone: 'info' },
            { icon: '②', title: t('i09w.why.2.t'), src: 'RULE', tone: 'info' },
            { icon: '③', title: t('i09w.why.3.t'), text: t('i09w.why.3.m'), src: 'RULE', tone: 'info' },
          ]}
        />
        <Tbl
          title={t('i09w.rules')}
          cols={[
            { t: t('i09w.rules.rule'), s: 'RULE' },
            { t: t('i09w.rules.statement'), s: 'RULE' },
          ]}
        >
          {[1, 2, 3, 4].map((n) => (
            <tr key={n}>
              <td>{t(`i09w.rules.${n}.r`)}</td>
              <td>{t(`i09w.rules.${n}.s`)}</td>
            </tr>
          ))}
        </Tbl>
      </div>
      <MemberOrder member={selected} maker={maker} mayCreate={mayCreate} />
      <RegisterSection id="i09-register" label={t('i09w.register')} open={open} onToggle={setOpen}>
        {open && <DeputationRegister />}
      </RegisterSection>
      {viewing && (
        <ViewDialog
          screen={t('screen.I-09')}
          onClose={() => setViewing(null)}
          rows={[
            { k: t('i09w.col.member'), v: `${viewing.eis} ${viewing.name}`, s: 'HRMS' },
            { k: t('i09w.col.organisation'), v: viewing.organisation ?? DASH, s: 'ENT' },
            {
              k: t('i09w.col.period'),
              v: `${date(viewing.from)} → ${viewing.to ? date(viewing.to) : t('i09.open')}`,
              s: 'ENT',
            },
            { k: t('i09w.col.outstanding'), v: money(viewing.outstanding), s: 'CALC' },
            { k: t('i09w.col.instalment'), v: money(viewing.instalment), s: 'ESS' },
            { k: t('i09w.col.balance'), v: money(viewing.balanceAfter), s: 'CALC' },
            { k: t('i09w.col.contribution'), v: money(viewing.contribution), s: 'ESS' },
            { k: t('i09w.col.status'), v: t(`i09w.st.${viewing.status}`), s: 'SYS' },
          ]}
        />
      )}
    </>
  );
}

/** The order card of the chosen member: the remittance evidence of their latest entry (SRS-G-31: added, never replaced). */
function MemberOrder({
  member,
  maker,
  mayCreate,
}: {
  member: DeputedRow | null;
  maker: boolean;
  mayCreate: boolean;
}) {
  const { t } = useI18n();
  const detail = useDeputedMember(member?.eis ?? '');
  const types = useDocumentTypes(true);
  const add = useAddDeputationEvidence();
  const entries = detail.data?.entries ?? [];
  const entry = entries.find((e) => e.evidence.length > 0) ?? entries[0];
  const stored: StoredOrder[] = (entry?.evidence ?? []).map((f) => ({
    id: f.id,
    fileName: f.fileName,
    documentTypeCode: null,
    documentTypeName: t(`i09.type.${entry?.type ?? 'RECOVERY_INSTALMENT'}`),
    referenceNumber: null,
    uploadedBy: f.uploadedBy,
    uploadedAt: f.uploadedAt,
  }));
  return (
    <OrderCopyCard
      key={entry?.id ?? 0}
      hint={t('i09w.ord.hint')}
      canAttach={mayCreate && entry !== undefined}
      readOnly={!mayCreate && !maker}
      stored={stored}
      storedError={detail.error ?? types.error}
      fileHref={(fileId) => deputationEvidenceUrl(entry?.id ?? 0, fileId)}
      today={todayIso()}
      attachmentRequired
      referenceRequired={(code) => types.data?.find((d) => d.code === code)?.referenceRequired ?? false}
      onAttach={async (o) => {
        if (!entry) return;
        for (const file of o.files) {
          await add.mutateAsync({
            id: entry.id,
            evidence: {
              documentType: o.documentType,
              reference: o.reference.trim(),
              orderDate: o.orderDate,
              issuingAuthority: o.issuingOffice.trim(),
              file,
            },
          });
        }
      }}
    />
  );
}
