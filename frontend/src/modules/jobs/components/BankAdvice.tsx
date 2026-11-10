'use client';

import { useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { can, periodLabel, useMe } from '@/shared/session';
import { amount, dateTime, Problem, Src, useToast, withBase } from '@/shared/ui';
import { Act, Explained, Locked, Tbl, Tiles, WfTile } from '@/shared/ui/wireframe';
import type { SuspendInput } from '../api/suspensionApi';
import type { BankOverview } from '../api/bankAdviceApi';
import { adviceTone, releaseStatus, sumOf } from '../business/bankAdviceRules';
import { crore } from '../business/cycleRules';
import { useBankOverview, useBankPeriods } from '../orchestration/bankAdviceQueries';
import { useSuspensionAction } from '../orchestration/suspensionQueries';
import { ReleaseSuspensions, SuspendDialog } from './ReleaseSuspensions';

const DASH = '—';

/** yyyymm as the session's period text. */
function monthOf(yymm: string): string {
  return `${yymm.slice(0, 4)}-${yymm.slice(4)}`;
}

/**
 * O-02 Bank Advice & EBS Posting as Wireframes v6.1 draws it (OD-121; SRS-15-34, SRS-15-35, SRS-15-41, l.1707-1713,
 * US-GAP-147, FRS §6.11.3, §11.4.2): the two banners (the emergency stop with its live status, the release rule), the
 * tiles, the Bank-wise Disbursement Summary and the GL Posting Preview read from the month's results. Generating the
 * advice and posting to EBS stay disabled with the reason until the period is locked and the bank layout and EBS
 * contract are supplied (API-23-6, API-23-2): nothing is sent. ⛔ Emergency stop opens the suspension dialog for the
 * holder of O-02 SUSPEND at Headquarters (BR-SEC-006); the pack draws it enabled for the Administrator, who holds no
 * such right here (OD-135): pressing it says why and nothing is sent (WF-1900). The suspensions, the undrawn fields of
 * SRS l.1707-1713 and the resumption stay in closed sections.
 */
export function BankAdvice() {
  const { t, locale } = useI18n();
  const toast = useToast();
  const me = useMe();
  const [picked, setPicked] = useState('');
  const [stopping, setStopping] = useState(false);
  const [suspensionsOpen, setSuspensionsOpen] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const overview = useBankOverview(picked);
  const mayChoose = can(me.data, 'O-01', 'VIEW');
  const periods = useBankPeriods(mayChoose);
  const action = useSuspensionAction();
  const o = overview.data;
  const mayStop = can(me.data, 'O-02', 'SUSPEND') && me.data?.atHeadquarters === true;
  const stopDrawnOn = me.data?.roles?.includes('SYSTEM_ADMINISTRATOR') === true;
  const contractWhy = t('o02w.why.contract');
  const locked = o?.locked === true;
  const shownMonth = o ? periodLabel(monthOf(o.yymm), locale) : DASH;
  const bankNames = (o?.bankRows ?? []).filter((b) => b.bank !== '-').map((b) => b.bank);

  async function suspend(body: SuspendInput) {
    setStopping(false);
    setError(null);
    try {
      await action.mutateAsync({ kind: 'suspend', body });
      toast(t('o02.suspendedDone'));
      setSuspensionsOpen(true);
    } catch (cause) {
      setError(cause);
    }
  }

  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sp" />
        <Locked label={t('o02w.act.generate')} why={locked ? contractWhy : t('o02w.why.generate')} />
        <Locked label={t('o02w.act.post')} why={locked ? contractWhy : t('o02w.why.post')} />
        {mayStop ? (
          <Act label={t('o02w.act.stop')} onClick={() => setStopping(true)} />
        ) : stopDrawnOn ? (
          <Explained label={t('o02w.act.stop')} why={t('o02w.why.stop')} />
        ) : (
          <Locked label={t('o02w.act.stop')} why={t('o02w.why.stop')} />
        )}
      </div>
      <div className="keep">
        <div className="anno on fl-anno-stop">
          <b>{t('o02w.stopLead')}</b> {t('o02w.stopText')} {t('o02w.status')}{' '}
          <b>{t(o?.suspended ? 'o02w.status.active' : 'o02w.status.inactive')}</b>.
        </div>
      </div>
      <div className="keep">
        <div className="anno on">
          <b>{t('o02w.otpLead')}</b> {t('o02w.otpText')}
        </div>
      </div>
      <Problem error={overview.error ?? error} />
      <Tiles>
        <WfTile
          icon="₹"
          label={t('o02w.tile.net')}
          src="CALC"
          big={t('o02w.crore', [crore(o?.net ?? 0)])}
          sub={t(locked ? 'o02w.tile.netLocked' : 'o02w.tile.netProvisional')}
        />
        <WfTile
          icon="🏦"
          label={t('o02w.tile.banks')}
          src="HRMS"
          tone="pur"
          big={o?.banks ?? 0}
          sub={bankNames.join(', ')}
        />
        <WfTile
          icon="⛔"
          label={t('o02w.tile.blocked')}
          src="SYS"
          tone="bad"
          bad
          big={amount(o?.blocked ?? 0)}
          sub={t('o02w.tile.blockedSub')}
        />
        <WfTile
          icon="📮"
          label={t('o02w.tile.ebs')}
          src="SYS"
          big={t(`o02w.ebs.${o?.ebsStatus ?? 'IDLE'}`)}
          sub={ebsSub(o, t)}
        />
      </Tiles>
      <div className="g2">
        <BankTable o={o} contractWhy={contractWhy} />
        <GlTable o={o} mappingAllowed={can(me.data, 'M-24', 'VIEW')} />
      </div>
      <details className="card fl-mt" data-testid="o02-release-details">
        <summary className="sub2">{t('o02w.section.details')}</summary>
        <div className="bd">
          <div className="kv">
            <div className="k">{t('o02w.field.period')}</div>
            <div>
              {mayChoose ? (
                <select
                  aria-label={t('o02w.field.period')}
                  value={o?.yymm ?? ''}
                  onChange={(e) => setPicked(e.target.value)}
                >
                  {(periods.data ?? []).map((p) => (
                    <option key={p.yymm} value={p.yymm}>
                      {periodLabel(monthOf(p.yymm), locale)}
                    </option>
                  ))}
                </select>
              ) : (
                shownMonth
              )}
            </div>
            <div className="k">{t('o02w.field.release')}</div>
            <div data-testid="o02-release-status">{o ? t(`o02w.release.${releaseStatus(o)}`) : DASH}</div>
            <div className="k">{t('o02w.field.excluded')}</div>
            <div>{t('o02w.field.excludedNote', [o?.blocked ?? 0])}</div>
          </div>
          <table className="t fl-mt" aria-label={t('o02w.field.invoices')}>
            <thead>
              <tr>
                <th>
                  {t('o02w.invoice.status')} <Src s="SYS" />
                </th>
                <th className="num">
                  {t('o02w.invoice.count')} <Src s="CALC" />
                </th>
                <th>
                  {t('o02w.invoice.sent')} <Src s="SYS" />
                </th>
              </tr>
            </thead>
            <tbody>
              {(o?.invoices ?? []).map((i) => (
                <tr key={i.status}>
                  <td>{t(`o02w.invoice.${i.status}`)}</td>
                  <td className="num">{amount(i.count)}</td>
                  <td>{dateTime(i.sentToApps)}</td>
                </tr>
              ))}
              {o?.invoices.length === 0 && (
                <tr>
                  <td colSpan={3}>
                    <p className="fl-hint">{t('o02w.invoice.none')}</p>
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </details>
      <details
        className="card fl-mt"
        data-testid="o02-suspensions"
        open={suspensionsOpen}
        onToggle={(e) => setSuspensionsOpen(e.currentTarget.open)}
      >
        <summary className="sub2">{t('o02w.section.suspensions')}</summary>
        <ReleaseSuspensions embedded />
      </details>
      {stopping && <SuspendDialog onCancel={() => setStopping(false)} onConfirm={(b) => void suspend(b)} />}
    </div>
  );
}

function ebsSub(o: BankOverview | undefined, t: (key: string) => string): string {
  if (o && o.ebsStatus !== 'IDLE') return '';
  return t(o?.locked ? 'o02w.ebs.subLocked' : 'o02w.ebs.subIdle');
}

/** The drawn Bank-wise Disbursement Summary: amounts in crore, with a total foot. */
function BankTable({ o, contractWhy }: { o: BankOverview | undefined; contractWhy: string }) {
  const { t } = useI18n();
  const rows = o?.bankRows ?? [];
  return (
    <Tbl
      title={t('o02w.banks')}
      acts={<Explained label={t('o02w.act.fileLayout')} why={contractWhy} kind="sm" />}
      cols={[
        { t: t('o02w.col.bank'), s: 'HRMS' },
        { t: t('o02w.col.members'), s: 'CALC', num: true },
        { t: t('o02w.col.net'), s: 'CALC', num: true },
        { t: t('o02w.col.blocked'), s: 'SYS', num: true },
        { t: t('o02w.col.format'), s: 'ENT' },
        { t: t('o02w.col.status'), s: 'SYS' },
      ]}
      foot={
        o && (
          <tr>
            <td>
              <b>{t('o02w.total')}</b>
            </td>
            <td className="num">
              <b>{amount(o.members)}</b>
            </td>
            <td className="num">
              <b>{crore(o.net)}</b>
            </td>
            <td className="num">
              <b>{amount(o.blocked)}</b>
            </td>
            <td />
            <td />
          </tr>
        )
      }
    >
      {rows.map((b) => (
        <tr key={b.bank} data-reference={b.bank}>
          <td>{b.bank === '-' ? t('o02w.noBank') : b.bank}</td>
          <td className="num">{amount(b.members)}</td>
          <td className="num">{crore(b.net)}</td>
          <td className="num">{amount(b.blocked)}</td>
          <td>{b.fileFormat ? <span className="pill p-ok">{b.fileFormat}</span> : DASH}</td>
          <td>
            <span className={`pill ${adviceTone(b.status)}`}>{t(`o02w.advice.${b.status}`)}</span>
          </td>
        </tr>
      ))}
      {o && rows.length === 0 && (
        <tr className="fl-state-row">
          <td colSpan={6}>
            <p className="fl-hint">{t('o02w.noBanks')}</p>
          </td>
        </tr>
      )}
    </Tbl>
  );
}

/** The drawn GL Posting Preview; no statement yet: the table says so in its body, as the state row of a list. */
function GlTable({ o, mappingAllowed }: { o: BankOverview | undefined; mappingAllowed: boolean }) {
  const { t } = useI18n();
  const gl = o?.gl ?? [];
  const cell = (v: number | null) => (v ? crore(v) : DASH);
  return (
    <Tbl
      title={t('o02w.gl')}
      acts={
        mappingAllowed ? (
          <Act label={t('o02w.act.mapping')} kind="sm" href={withBase('/payroll/?s=M-24')} />
        ) : (
          <Explained label={t('o02w.act.mapping')} why={t('reason.noPermission')} kind="sm" />
        )
      }
      cols={[
        { t: t('o02w.col.head'), s: 'RULE' },
        { t: t('o02w.col.account'), s: 'RULE' },
        { t: t('o02w.col.debit'), s: 'CALC', num: true },
        { t: t('o02w.col.credit'), s: 'CALC', num: true },
      ]}
      foot={
        gl.length > 0 && (
          <tr>
            <td>
              <b>{t('o02w.total')}</b>
            </td>
            <td />
            <td className="num">
              <b>{crore(sumOf(gl, 'debit'))}</b>
            </td>
            <td className="num">
              <b>{crore(sumOf(gl, 'credit'))}</b>
            </td>
          </tr>
        )
      }
    >
      {gl.map((l) => (
        <tr key={`${l.account}-${l.head}`}>
          <td>{l.head}</td>
          <td>{l.account}</td>
          <td className="num">{cell(l.debit)}</td>
          <td className="num">{cell(l.credit)}</td>
        </tr>
      ))}
      {o && gl.length === 0 && (
        <tr className="fl-state-row">
          <td colSpan={4}>
            <p className="fl-hint">{t('o02w.noGl')}</p>
          </td>
        </tr>
      )}
    </Tbl>
  );
}
