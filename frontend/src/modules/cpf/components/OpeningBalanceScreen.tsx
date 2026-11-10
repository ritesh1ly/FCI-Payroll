'use client';

import { useCallback, useState } from 'react';
import { todayIso, useDocumentTypes } from '@/modules/general';
import { useI18n } from '@/shared/i18n';
import { useReference } from '@/shared/reference';
import { can, useMe } from '@/shared/session';
import { amount, Problem, ServerPagination } from '@/shared/ui';
import { RegisterSection } from '@/shared/ui/masterParts';
import { OrderCopyCard, type StoredOrder } from '@/shared/ui/orderCopy';
import {
  Act,
  Explained,
  Locked,
  Panel,
  type PanelItem,
  RowActs,
  Tbl,
  Tiles,
  ViewDialog,
  WfTile,
} from '@/shared/ui/wireframe';
import type { CorrectionLine } from '../api/cpfScreensApi';
import { evidenceUrl } from '../orchestration/cpfQueries';
import {
  cpfScreenKeys,
  cpfScreenPages,
  useCorrectionBoard,
  useCpfApprove,
} from '../orchestration/cpfScreenQueries';
import { CORRECTION_STATUS_PILL } from '../business/openingBalanceRules';
import type { CorrectionStatus } from '../api/cpfApi';
import { OpeningBalanceCorrection as CorrectionRegister } from './OpeningBalanceCorrection';

const DASH = '—';
const NUM = new Intl.NumberFormat('en-IN');
const REASONS: [string, PanelItem['src']][] = [
  ['①', 'SYS'],
  ['②', 'INTF'],
  ['③', 'ENT'],
  ['④', 'ENT'],
  ['⑤', 'ENT'],
];
const CONTROLS: [string, PanelItem['src'], boolean][] = [
  ['🧾', 'SYS', true],
  ['📈', 'CALC', false],
  ['⛔', 'RULE', false],
  ['🔐', 'SYS', false],
  ['🔄', 'INTF', true],
];

/** Open at a tier: the corrections an approver of that tier decides. */
const mayDecide = (status: string) => status === 'PENDING_AGM_CPF' || status === 'PENDING_HQ_CPF';

/**
 * I-22 PF / CPF Opening Balance Correction as Wireframes v6.1 draws it (OD-121; SRS-6-43, SRS-6-44, BR-CPF-025 … 027,
 * BR-APPR-008): the tiles, one row per corrected part, the permitted reasons, the controls and the evidence of the
 * chosen request. The member lookup, the correction form with its OTP-gated route (CPF_OPENING_BALANCE_CORRECTION at each
 * approval tier) and the register with history and evidence stay whole in the closed register. The interest effect is
 * a dash until FCI gives the method (Q-072).
 */
export function OpeningBalanceCorrection() {
  const { t } = useI18n();
  const me = useMe();
  const reference = useReference();
  const board = useCorrectionBoard();
  const roles = me.data?.roles ?? [];
  const mayCreate = can(me.data, 'I-22', 'CREATE');
  const maker = roles.includes('PAYROLL_MAKER');
  const approve = useCpfApprove('I-22', 'i22w.why.agm');
  const [open, setOpen] = useState(false);
  const [viewing, setViewing] = useState<CorrectionLine | null>(null);
  const [selected, setSelected] = useState<CorrectionLine | null>(null);
  const b = board.data;
  const onRows = useCallback((rows: CorrectionLine[]) => {
    setSelected((prev) => rows.find((r) => r.ref === prev?.ref && r.part === prev?.part) ?? rows[0] ?? null);
  }, []);
  const openRegister = () => setOpen(true);
  const reasonOf = (r: CorrectionLine) => reference.label('OB_CORRECTION_REASON', r.reasonCode);
  const statusOf = (r: CorrectionLine) => t(`i22.status.${r.status}`);
  return (
    <>
      <div className="toolbar">
        <span className="sp" />
        <Act label={t('i22w.act.register')} onClick={openRegister} />
        {mayCreate && <Act label={t('i22w.act.raise')} kind="pri" onClick={openRegister} />}
        {!mayCreate && maker && (
          <Explained label={t('i22w.act.raise')} kind="pri" why={t('i22w.why.notManager')} />
        )}
        {!mayCreate && !maker && (
          <Locked label={t('i22w.act.raise')} kind="pri" why={t('i22w.why.raiseRole')} />
        )}
      </div>
      <Problem error={board.error} />
      <Tiles>
        <WfTile
          icon="⚖️"
          label={t('i22w.tile.open')}
          src="SYS"
          big={NUM.format(b?.open ?? 0)}
          sub={t('i22w.tile.openSub')}
        />
        <WfTile
          icon="✍️"
          label={t('i22w.tile.agm')}
          src="SYS"
          big={NUM.format(b?.pendingAgm ?? 0)}
          sub={t('i22w.tile.agmSub')}
        />
        <WfTile
          icon="🏛"
          label={t('i22w.tile.hq')}
          src="SYS"
          big={NUM.format(b?.pendingHq ?? 0)}
          sub={t('i22w.tile.hqSub')}
        />
        <WfTile
          icon="📈"
          label={t('i22w.tile.interest')}
          src="CALC"
          big={b?.interestQueued == null ? DASH : amount(b.interestQueued)}
          sub={t('i22w.tile.interestSub')}
        />
      </Tiles>
      <Tbl
        title={t('i22w.table')}
        acts={<Act label={t('i22w.act.registerSm')} kind="sm" onClick={openRegister} />}
        cols={[
          { t: t('i22w.col.request'), s: 'SYS' },
          { t: t('i22w.col.member'), s: 'HRMS' },
          { t: t('i22w.col.part'), s: 'ENT' },
          { t: t('i22w.col.current'), s: 'SYS', num: true },
          { t: t('i22w.col.corrected'), s: 'ENT', num: true },
          { t: t('i22w.col.difference'), s: 'CALC', num: true },
          { t: t('i22w.col.reason'), s: 'ENT' },
          { t: t('i22w.col.evidence'), s: 'ENT' },
          { t: t('i22w.col.interest'), s: 'CALC', num: true },
          { t: t('i22w.col.status'), s: 'SYS' },
          { t: t('i22w.col.actions'), s: 'SYS' },
        ]}
      >
        <ServerPagination
          queryKey={cpfScreenKeys.correctionLines}
          fetchPage={cpfScreenPages.correctionLines}
          empty={t('i22.noCorrections')}
          onRows={onRows}
        >
          {(rows) =>
            rows.map((r) => (
              <tr
                key={`${r.ref}-${r.part}`}
                aria-selected={selected?.ref === r.ref && selected?.part === r.part}
                className={
                  selected?.ref === r.ref && selected?.part === r.part ? 'fl-row-selected' : undefined
                }
                onClick={() => setSelected(r)}
              >
                <td>
                  <b>{r.ref}</b>
                </td>
                <td>
                  {r.cpfCode ?? r.eis} {r.name}
                </td>
                <td>{t(`i22.part.${r.part}`)}</td>
                <td className="num">{amount(r.current)}</td>
                <td className="num">{amount(r.corrected)}</td>
                <td className="num">{amount(r.difference)}</td>
                <td>{reasonOf(r)}</td>
                <td>{r.evidence ?? DASH}</td>
                <td className="num">{r.interestEffect === null ? DASH : amount(r.interestEffect)}</td>
                <td>
                  <span
                    className={`pill ${CORRECTION_STATUS_PILL[r.status as CorrectionStatus] ?? 'p-gray'}`}
                  >
                    {statusOf(r)}
                  </span>
                </td>
                <td>
                  <RowActs
                    onView={() => setViewing(r)}
                    onEdit={mayCreate ? openRegister : undefined}
                    editWhy={t('i22w.why.notManager')}
                    onApprove={approve ? () => approve(mayDecide(r.status)) : undefined}
                    viewName={t('i22w.viewName', [r.ref, t(`i22.part.${r.part}`)])}
                    editName={t('i22w.editName', [r.ref, t(`i22.part.${r.part}`)])}
                  />
                </td>
              </tr>
            ))
          }
        </ServerPagination>
      </Tbl>
      <div className="g2 fl-mt">
        <Panel
          title={t('i22w.reasons')}
          items={REASONS.map(([icon, src], i) => ({
            icon,
            src,
            tone: 'info' as const,
            title: t(`i22w.reason.${i + 1}.t`),
            text: t(`i22w.reason.${i + 1}.m`),
          }))}
        />
        <Panel
          title={t('i22w.controls')}
          items={CONTROLS.map(([icon, src, text], i) => ({
            icon,
            src,
            tone: 'info' as const,
            title: t(`i22w.control.${i + 1}.t`),
            text: text ? t(`i22w.control.${i + 1}.m`) : undefined,
          }))}
        />
      </div>
      <CorrectionOrder line={selected} maker={maker || mayCreate} />
      <RegisterSection id="i22-register" label={t('i22w.register')} open={open} onToggle={setOpen}>
        {open && <CorrectionRegister />}
      </RegisterSection>
      {viewing && (
        <ViewDialog
          screen={t('screen.I-22')}
          onClose={() => setViewing(null)}
          rows={[
            { k: t('i22w.col.request'), v: viewing.ref, s: 'SYS' },
            { k: t('i22w.col.member'), v: `${viewing.cpfCode ?? viewing.eis} ${viewing.name}`, s: 'HRMS' },
            { k: t('i22w.col.part'), v: t(`i22.part.${viewing.part}`), s: 'ENT' },
            { k: t('i22w.col.current'), v: amount(viewing.current), s: 'SYS' },
            { k: t('i22w.col.corrected'), v: amount(viewing.corrected), s: 'ENT' },
            { k: t('i22w.col.difference'), v: amount(viewing.difference), s: 'CALC' },
            { k: t('i22w.col.reason'), v: reasonOf(viewing), s: 'ENT' },
            { k: t('i22w.col.evidence'), v: viewing.evidence ?? DASH, s: 'ENT' },
            {
              k: t('i22w.col.interest'),
              v:
                viewing.interestEffect === null
                  ? t('i22.interestEffectNotConfigured')
                  : amount(viewing.interestEffect),
              s: 'CALC',
            },
            { k: t('i22w.col.status'), v: statusOf(viewing), s: 'SYS' },
          ]}
        />
      )}
    </>
  );
}

/**
 * The order card as drawn, showing the evidence of the chosen request. Evidence is captured with the request (BR-CPF-026:
 * "submit blocked" without it) and has no later-attach API, so picking a file here says where it is taken (WF-272).
 */
function CorrectionOrder({ line, maker }: { line: CorrectionLine | null; maker: boolean }) {
  const { t } = useI18n();
  const types = useDocumentTypes(true);
  const stored: StoredOrder[] =
    line && line.evidenceId !== null
      ? [
          {
            id: line.evidenceId,
            fileName: line.fileName ?? '',
            documentTypeCode: null,
            documentTypeName: line.documentType,
            referenceNumber: line.evidence,
            uploadedBy: line.uploadedBy ?? '',
            uploadedAt: line.uploadedAt ?? '',
          },
        ]
      : [];
  return (
    <OrderCopyCard
      key={line?.ref ?? ''}
      hint={t('i22w.ord.hint')}
      canAttach={false}
      readOnly={!maker}
      stored={stored}
      storedError={types.error}
      fileHref={(fileId) => evidenceUrl(line?.ref ?? '', fileId)}
      today={todayIso()}
      attachmentRequired
      referenceRequired={(code) => types.data?.find((d) => d.code === code)?.referenceRequired ?? false}
      onAttach={async () => undefined}
    />
  );
}
