'use client';

import { useCallback, useState } from 'react';
import { useDocumentTypes } from '@/modules/general';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { businessToday, finYearOfDay } from '@/shared/time';
import { amount, date, Problem, ServerPagination, useToast, withBase } from '@/shared/ui';
import { RegisterSection } from '@/shared/ui/masterParts';
import { OrderCopyCard } from '@/shared/ui/orderCopy';
import { Act, Explained, Locked, Panel, RowActs, Tbl, Tiles, WfTile } from '@/shared/ui/wireframe';
import type { DeclarationLine, SectionLine } from '../api/screensApi';
import { declarationStatus, proofLabel } from '../business/screenRules';
import {
  roundOrderUrl,
  screenKeys,
  screenPages,
  useAttachRoundOrder,
  useDeclarationSummary,
  useRoundOrders,
} from '../orchestration/screensQueries';
import { DeclarationReview } from './DeclarationReview';

function percent(part: number, whole: number): number {
  return whole === 0 ? 0 : Math.round((part * 100) / whole);
}

function regimeKey(regime: string | null): string {
  if (regime === null) return 'txw.i07.regime.none';
  return regime === 'N' ? 'e01t.regime.NEW' : 'e01t.regime.OLD';
}

/**
 * I-07 Income-Tax Declarations & Regime as Wireframes v6.1 draws it (OD-121; SRS-7-1, SRS-7-20, SRS-7-24, BR-TAX-001,
 * BR-TAX-014): the tiles, every member of the offices with the latest round of the year (a member with no regime has a
 * line: it blocks that member's tax), the section table read-only, the validations and the order card. Verification,
 * return and regime reset stay whole in the closed register; the Checker decides and freezes in W-01.
 */
export function DeclarationsScreen() {
  const { t } = useI18n();
  const toast = useToast();
  const me = useMe();
  const mayVerify = can(me.data, 'I-07', 'SUBMIT');
  const checker = can(me.data, 'I-07', 'APPROVE');
  const finYear = finYearOfDay(businessToday());
  const summary = useDeclarationSummary(finYear);
  const s = summary.data;
  const [open, setOpen] = useState(false);
  const [selected, setSelected] = useState<DeclarationLine | null>(null);
  const shown = useCallback((page: DeclarationLine[]) => {
    setSelected((prev) => prev ?? page.find((l) => l.roundId !== null) ?? null);
  }, []);
  const toInbox = () => window.location.assign(withBase('/payroll/?s=W-01'));
  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sp" />
        <Act label={t('txw.i07.proofVerification')} onClick={() => setOpen(true)} />
        {mayVerify ? (
          <Act
            label={t('txw.i07.recompute')}
            kind="pri"
            onClick={() => {
              void summary.refetch();
              toast(t('txw.i07.recomputed'));
            }}
          />
        ) : (
          <Locked label={t('txw.i07.recompute')} why={t('txw.why.makerOnly')} />
        )}
      </div>
      <Problem error={summary.error} />
      <Tiles>
        <WfTile
          icon="⚖️"
          label={t('txw.i07.tile.split')}
          src="ESS"
          big={`${percent(s?.newRegime ?? 0, s?.members ?? 0)}% / ${percent(s?.oldRegime ?? 0, s?.members ?? 0)}%`}
          sub={t('txw.i07.tile.splitSub', [s?.newRegime ?? 0, s?.oldRegime ?? 0])}
        />
        <WfTile
          icon="📝"
          label={t('txw.i07.tile.received')}
          src="ESS"
          big={s?.received ?? 0}
          sub={t('txw.i07.tile.receivedSub', [percent(s?.received ?? 0, s?.members ?? 0)])}
          tone="ok"
        />
        <WfTile
          icon="⏳"
          label={t('txw.i07.tile.pending')}
          src="SYS"
          big={s?.pending ?? 0}
          sub={t('txw.i07.tile.pendingSub')}
          tone="warn"
        />
        <WfTile
          icon="⛔"
          label={t('txw.i07.tile.noRegime')}
          src="SYS"
          big={s?.notSelected ?? 0}
          sub={t('txw.i07.tile.noRegimeSub')}
          tone="bad"
          bad={(s?.notSelected ?? 0) > 0}
        />
      </Tiles>
      <Tbl
        title={t('txw.i07.register', [finYear])}
        label={t('txw.i07.registerLabel')}
        acts={
          <>
            <Act label={t('txw.i07.proofs')} kind="sm" onClick={() => setOpen(true)} />
            {checker ? (
              <Act label={t('txw.i07.freeze')} kind="sm" onClick={toInbox} />
            ) : (
              <Locked label={t('txw.i07.freeze')} kind="sm" why={t('txw.why.checkerOnly')} />
            )}
          </>
        }
        cols={[
          { t: t('txw.i07.col.member'), s: 'HRMS' },
          { t: t('txw.i07.col.regime'), s: 'ESS' },
          { t: t('txw.i07.col.frozen'), s: 'SYS' },
          { t: t('txw.i07.col.d80c'), s: 'ESS', num: true },
          { t: t('txw.i07.col.limit'), s: 'RULE', num: true },
          { t: t('txw.i07.col.d80d'), s: 'ESS', num: true },
          { t: t('txw.i07.col.d24b'), s: 'ESS', num: true },
          { t: t('txw.i07.col.allowed'), s: 'CALC', num: true },
          { t: t('txw.i07.col.proof'), s: 'ENT' },
          { t: t('txw.i07.col.status'), s: 'SYS' },
          { t: t('txw.i07.col.actions'), s: 'SYS' },
        ]}
      >
        <ServerPagination
          queryKey={screenKeys.declarationMembers(finYear)}
          fetchPage={screenPages.declarationMembers(finYear)}
          empty={t('txw.i07.none')}
          onRows={shown}
        >
          {(page) =>
            page.map((l) => (
              <MemberRow
                key={l.employeeId}
                line={l}
                selected={selected?.employeeId === l.employeeId}
                mayVerify={mayVerify}
                checker={checker}
                onSelect={() => setSelected(l)}
                onOpen={() => setOpen(true)}
                onApprove={toInbox}
              />
            ))
          }
        </ServerPagination>
      </Tbl>
      <Sections finYear={finYear} sections={s?.sections ?? []} />
      <Panel
        title={t('txw.i07.validations')}
        items={[
          {
            icon: '⚖️',
            tone: 'bad',
            title: t('txw.i07.val.1'),
            src: 'SYS',
            text: t('txw.i07.val.1s'),
            right: s?.notSelected ?? 0,
          },
          {
            icon: '📝',
            tone: 'warn',
            title: t('txw.i07.val.2'),
            src: 'RULE',
            text: t('txw.i07.val.2s'),
            right: s?.capped ?? 0,
          },
          {
            icon: '📄',
            tone: 'info',
            title: t('txw.i07.val.3'),
            src: 'ENT',
            text: t('txw.i07.val.3s'),
            right: s?.unproved ?? 0,
          },
          {
            icon: '🔒',
            tone: 'pur',
            title: t('txw.i07.val.4'),
            src: 'RULE',
            text: t('txw.i07.val.4s'),
            right: <span className="pill p-ok">{t('txw.i07.on')}</span>,
          },
        ]}
      />
      <RoundOrders round={selected ? selected.roundId : (s?.orderRound ?? null)} mayAttach={mayVerify} />
      <RegisterSection label={t('txw.i07.section')} open={open} onToggle={setOpen} id="i07-register">
        {open && <DeclarationReview />}
      </RegisterSection>
    </div>
  );
}

function MemberRow({
  line: l,
  selected,
  mayVerify,
  checker,
  onSelect,
  onOpen,
  onApprove,
}: {
  line: DeclarationLine;
  selected: boolean;
  mayVerify: boolean;
  checker: boolean;
  onSelect: () => void;
  onOpen: () => void;
  onApprove: () => void;
}) {
  const { t } = useI18n();
  const proof = proofLabel(l);
  const applies = l.regime === 'O';
  return (
    <tr
      data-reference={l.eis}
      className={selected ? 'fl-row-selected' : undefined}
      aria-selected={selected}
      onClick={onSelect}
    >
      <td>
        {l.eis} {l.name}
      </td>
      <td>{t(regimeKey(l.regime))}</td>
      <td>{l.frozen ? date(l.frozenAt) : '—'}</td>
      <td className="num">{amount(l.declared80c)}</td>
      <td className="num">{applies ? amount(l.limit80c) : '—'}</td>
      <td className="num">{amount(l.declared80d)}</td>
      <td className="num">{amount(l.declared24b)}</td>
      <td className="num">{amount(l.allowed)}</td>
      <td>{proof ? t(proof) : '—'}</td>
      <td>
        <span className="pill">{t(declarationStatus(l))}</span>
      </td>
      <td onClick={(e) => e.stopPropagation()}>
        <RowActs
          viewName={`${t('common.view')} ${l.eis}`}
          editName={`${t('common.edit')} ${l.eis}`}
          onView={onOpen}
          onEdit={mayVerify ? onOpen : undefined}
          editWhy={t('txw.why.makerInputs')}
          onApprove={checker ? onApprove : undefined}
        />
      </td>
    </tr>
  );
}

/** The drawn table, read-only: the limits of the section master, the rules the rule engine holds. */
function Sections({ finYear, sections }: { finYear: string; sections: SectionLine[] }) {
  const { t } = useI18n();
  const me = useMe();
  const held = (code: string) => sections.find((s) => s.code === code);
  const mayOpen = can(me.data, 'M-18', 'VIEW');
  const row = (key: string, regime: string, value: string, from: string | undefined, status: string) => (
    <tr key={key}>
      <td>{key}</td>
      <td>{regime}</td>
      <td>{value}</td>
      <td>{date(from)}</td>
      <td>{status}</td>
    </tr>
  );
  const notConfigured = t('txw.i07.sec.notConfigured');
  const active = t('txw.i07.sec.active');
  const old = t('e01t.regime.OLD');
  const fresh = t('e01t.regime.NEW');
  const both = t('txw.i07.sec.both');
  const limit = (code: string) => {
    const s = held(code);
    return s?.limit == null ? notConfigured : `₹ ${amount(s.limit)}`;
  };
  const rule = (code: string) => (held(code) ? t('txw.i07.sec.configured') : notConfigured);
  const state = (code: string) => (held(code) ? active : '');
  return (
    <Tbl
      title={t('txw.i07.sections')}
      acts={
        mayOpen ? (
          <Act label={t('txw.i07.openRules')} kind="sm" href={withBase('/payroll/?s=M-18')} />
        ) : (
          <Explained label={t('txw.i07.openRules')} kind="sm" why={t('txw.i07.why.rules')} />
        )
      }
      cols={[
        { t: t('txw.i07.sec.col.section'), s: 'RULE' },
        { t: t('txw.i07.sec.col.regime'), s: 'RULE' },
        { t: t('txw.i07.sec.col.limit'), s: 'RULE' },
        { t: t('txw.i07.sec.col.from'), s: 'RULE' },
        { t: t('txw.i07.sec.col.status'), s: 'SYS' },
      ]}
    >
      {row('80C', old, limit('80C'), held('80C')?.effectiveFrom, state('80C'))}
      {row('80D', old, limit('80D'), held('80D')?.effectiveFrom, state('80D'))}
      {row(t('txw.i07.sec.housing'), old, limit('24(b)'), held('24(b)')?.effectiveFrom, state('24(b)'))}
      {row(
        t('txw.i07.sec.slabs', [finYear]),
        fresh,
        rule('TAX.SLAB.NEW'),
        held('TAX.SLAB.NEW')?.effectiveFrom,
        state('TAX.SLAB.NEW'),
      )}
      {row(t('txw.i07.sec.pt'), both, rule('PT.SLAB'), held('PT.SLAB')?.effectiveFrom, state('PT.SLAB'))}
      {row(t('txw.i07.sec.relief'), both, t('txw.i07.sec.reliefValue'), undefined, '')}
    </Tbl>
  );
}

/** The drawn order card of the selected round (SRS-G-30). */
function RoundOrders({ round, mayAttach }: { round: number | null; mayAttach: boolean }) {
  const { t } = useI18n();
  const stored = useRoundOrders(round);
  const types = useDocumentTypes(true);
  const attach = useAttachRoundOrder();
  return (
    <OrderCopyCard
      key={round ?? 0}
      hint={t('txw.i07.orderHint')}
      canAttach={mayAttach && round !== null}
      readOnly={!mayAttach}
      stored={stored.data}
      storedError={stored.error ?? types.error}
      fileHref={(fileId) => roundOrderUrl(round ?? 0, fileId)}
      today={businessToday()}
      attachmentRequired
      referenceRequired={(code) => types.data?.find((d) => d.code === code)?.referenceRequired ?? false}
      onAttach={async (o) => {
        if (round === null) return;
        for (const file of o.files) {
          await attach.mutateAsync({ id: round, order: { ...o, file } });
        }
        await stored.refetch();
      }}
    />
  );
}
