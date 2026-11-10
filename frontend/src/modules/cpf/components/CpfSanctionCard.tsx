'use client';

import { useCallback, useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { amount, ServerPagination, useToast } from '@/shared/ui';
import { Act, Panel, RowActs, Tbl, ViewDialog, WfTile } from '@/shared/ui/wireframe';
import type { Sanction } from '../api/cpfApi';
import {
  limitCheck,
  limitRuleKey,
  statusKey,
  tierAction,
  tierCell,
  waitingTier,
} from '../business/sanctionRules';
import { cpfKeys, sanctionPages, useSanctionsPending } from '../orchestration/cpfQueries';
import { useCpfApprove } from '../orchestration/cpfScreenQueries';

/** The "CPF Sanctions Pending" tile of I-04 (SRS-6-4): the requests waiting at a tier, by type. */
export function CpfPendingTile() {
  const { t } = useI18n();
  const s = useSanctionsPending().data;
  return (
    <WfTile
      icon="🏦"
      label={t('i04c.tile')}
      src="SYS"
      big={amount(s?.pending ?? 0)}
      sub={t('i04c.tileSub', [s?.advance ?? 0, s?.partFinal ?? 0, s?.finalPayment ?? 0])}
    />
  );
}

/**
 * The CPF cards of I-04 as Wireframes v6.1 draws them (OD-121; SRS-6-4, SRS-6-55, UC-05, BR-CPF-028 to 035):
 * the register with the limit rule, the eligible amount, the tier and the status; "Limit check" reads the stored
 * ceiling of the chosen request (tier 2 computes it; nothing is recomputed here) and the rules panel. The
 * tiers' actions stay in the closed section (CpfSanctions): ✎ Edit opens the request there for the tier
 * that may act; tier 3 is decided in W-01 with the CPF_SANCTION_FINAL_PAYMENT OTP. The pack draws Edit
 * for the Payroll Maker while the chain's rights belong to the CPF roles, so the Maker is told why on use.
 */
export function CpfSanctionCard({ onOpen }: { onOpen: (id: number) => void }) {
  const { t } = useI18n();
  const toast = useToast();
  const me = useMe();
  const may = (action: string) => can(me.data, 'I-04', action);
  const approve = useCpfApprove('I-04', 'i04c.tierWhy');
  const drawnEdit = may('LOAN_SANCTION') || may('EDIT') || may('SUBMIT');
  const [chosen, setChosen] = useState<Sanction | null>(null);
  const [viewing, setViewing] = useState<Sanction | null>(null);
  const shown = useCallback((page: Sanction[]) => {
    setChosen((prev) => (prev ? (page.find((s) => s.id === prev.id) ?? prev) : (page[0] ?? null)));
  }, []);

  const number = (s: Sanction) => s.sanctionNumber ?? t('i04c.requestNo', [s.id]);
  const rule = (s: Sanction) => {
    const key = limitRuleKey(s);
    return key ? t(key) : (s.limitRule ?? '');
  };
  const reason = (s: Sanction) => `${t(`i04.kind.${s.type}`)} · ${s.purposeName ?? s.purposeCode ?? '—'}`;
  const tier = (s: Sanction) => {
    const cell = tierCell(s);
    return cell ? t(cell.key) : '—';
  };

  function check() {
    if (!chosen) {
      toast(t('i04.none'));
      return;
    }
    toast(
      t(`i04c.check.${limitCheck(chosen)}`, [
        number(chosen),
        amount(chosen.requested),
        amount(chosen.ceiling),
      ]),
    );
  }

  return (
    <>
      <Tbl
        title={t('i04c.card')}
        acts={<Act label={t('i04c.limitCheck')} kind="sm" onClick={check} />}
        cols={[
          { t: t('i04c.col.number'), s: 'SYS' },
          { t: t('i04c.col.member'), s: 'HRMS' },
          { t: t('i04c.col.reason'), s: 'ESS' },
          { t: t('i04c.col.rule'), s: 'RULE' },
          { t: t('i04c.col.eligible'), s: 'CALC', num: true },
          { t: t('i04c.col.requested'), s: 'ESS', num: true },
          { t: t('i04c.col.sanctioned'), s: 'ENT', num: true },
          { t: t('i04c.col.tier'), s: 'SYS' },
          { t: t('i04c.col.status'), s: 'SYS' },
          { t: t('i04c.col.actions'), s: 'SYS' },
        ]}
      >
        <ServerPagination
          queryKey={[...cpfKeys.sanctionList(''), 'card']}
          fetchPage={sanctionPages.list('')}
          empty={t('i04.none')}
          onRows={shown}
        >
          {(page) =>
            page.map((s) => {
              const cell = tierCell(s);
              const inChain = waitingTier(s.stage) !== null;
              return (
                <tr key={s.id} data-sanction={s.id}>
                  <td>
                    <b>{number(s)}</b>
                  </td>
                  <td>
                    {s.eis} {s.memberName}
                  </td>
                  <td>{reason(s)}</td>
                  <td>{rule(s)}</td>
                  <td className="num">
                    {s.ceiling === null ? (
                      <span className="pill p-info">{t('i04c.eligiblePending')}</span>
                    ) : (
                      amount(s.ceiling)
                    )}
                  </td>
                  <td className="num">{amount(s.requested)}</td>
                  <td className="num">{amount(s.sanctioned)}</td>
                  <td>{cell ? <span className={`pill ${cell.pill}`}>{t(cell.key)}</span> : '—'}</td>
                  <td>
                    <span className={`pill ${s.capped ? 'p-warn' : 'p-info'}`}>{t(statusKey(s))}</span>
                  </td>
                  <td>
                    <RowActs
                      viewName={t('i04c.viewOf', [number(s)])}
                      editName={t('i04c.editOf', [number(s)])}
                      onView={() => {
                        setChosen(s);
                        setViewing(s);
                      }}
                      onEdit={
                        inChain && drawnEdit
                          ? () => {
                              setChosen(s);
                              if (tierAction(s.stage, may)) onOpen(s.id);
                              else toast(t('i04c.tierWhy'));
                            }
                          : undefined
                      }
                      editWhy={t(inChain ? 'i04c.editRole' : 'i04c.editDone')}
                      // Drawn on a request in the chain: an authority goes to W-01, the Checker is told who decides.
                      onApprove={approve && inChain ? () => approve(true) : undefined}
                    />
                  </td>
                </tr>
              );
            })
          }
        </ServerPagination>
      </Tbl>
      {viewing && (
        <ViewDialog
          screen={t('screen.I-04')}
          onClose={() => setViewing(null)}
          rows={[
            { k: t('i04c.col.number'), v: number(viewing), s: 'SYS' },
            { k: t('i04c.col.member'), v: `${viewing.eis} ${viewing.memberName}`, s: 'HRMS' },
            { k: t('i04c.col.reason'), v: reason(viewing), s: 'ESS' },
            { k: t('i04c.col.rule'), v: rule(viewing), s: 'RULE' },
            { k: t('i04c.col.eligible'), v: amount(viewing.ceiling), s: 'CALC' },
            { k: t('i04c.col.requested'), v: amount(viewing.requested), s: 'ESS' },
            { k: t('i04c.col.sanctioned'), v: amount(viewing.sanctioned), s: 'ENT' },
            { k: t('i04c.col.tier'), v: tier(viewing), s: 'SYS' },
            { k: t('i04c.col.status'), v: t(statusKey(viewing)), s: 'SYS' },
          ]}
          actions={
            <Act
              label={t('i04w.openRecord')}
              onClick={() => {
                onOpen(viewing.id);
                setViewing(null);
              }}
            />
          }
        />
      )}
      <Panel
        title={t('i04c.rules')}
        items={[
          {
            icon: '🚫',
            tone: 'bad',
            title: t('i04c.rule.covid'),
            src: 'RULE',
            text: t('i04c.rule.covidText'),
          },
          { icon: '1️⃣', tone: 'info', title: t('i04c.rule.one'), src: 'RULE', text: t('i04c.rule.oneText') },
          {
            icon: '#️⃣',
            tone: 'gray',
            title: t('i04c.rule.number'),
            src: 'SYS',
            text: t('i04c.rule.numberText'),
          },
          { icon: '🏠', tone: 'info', title: t('i04c.rule.purposes'), src: 'RULE' },
          {
            icon: '📊',
            tone: 'info',
            title: t('i04c.rule.basis'),
            src: 'CALC',
            text: t('i04c.rule.basisText'),
          },
          { icon: '✉️', tone: 'warn', title: t('i04c.rule.email'), src: 'HRMS' },
        ]}
      />
    </>
  );
}
