'use client';

import { useCallback, useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { useMe } from '@/shared/session';
import { amount, date, ServerPagination } from '@/shared/ui';
import { Explained, KvCard, Locked, Panel, Tbl } from '@/shared/ui/wireframe';
import type { SupplementaryRow } from '../api/cpfScreensApi';
import { cpfScreenKeys, cpfScreenPages } from '../orchestration/cpfScreenQueries';

const DASH = '—';
const rupees = (v: number | undefined) => (v === undefined ? DASH : `₹ ${amount(v)}`);
const EXCLUSIONS: [string, number][] = [
  ['⚖️', 1],
  ['🏛', 2],
  ['🏦', 3],
  ['📄', 4],
];

/**
 * I-12 CPF Supplementary Payment as Wireframes v6.1 draws it (OD-121; SRS-6-48, SRS-G-37, BR-AUDIT-009, BR-AUDIT-010): the
 * member and the new payment details of the chosen entry, the recoveries this screen excludes and the invoice status of
 * the entries. Read from cpf_supplementary_payment.
 *
 * Recording, the settlement letter, the release and the cancellation are not built: the amounts come from the
 * supplementary wages and the interest to the sanction date, and the final payment itself waits for FCI's method
 * (PENDING 07h-blocked, Q-072, Q-07-11). So each is drawn for the role the pack gives it and says why when pressed.
 */
export function SupplementaryPayment() {
  const { t } = useI18n();
  const me = useMe();
  const roles = me.data?.roles ?? [];
  const maker = roles.includes('PAYROLL_MAKER');
  const checker = roles.includes('PAYROLL_CHECKER');
  const [selected, setSelected] = useState<SupplementaryRow | null>(null);
  const notBuilt = t('i12w.why.notBuilt');
  const onRows = useCallback((rows: SupplementaryRow[]) => {
    setSelected((prev) => rows.find((r) => r.entry === prev?.entry) ?? rows[0] ?? null);
  }, []);
  const s = selected;
  return (
    <>
      <div className="toolbar">
        <span className="sp" />
        {maker ? (
          <Explained label={t('i12w.act.letter')} kind="pri" why={notBuilt} />
        ) : (
          <Locked label={t('i12w.act.letter')} kind="pri" why={t('i12w.why.letter')} />
        )}
        {maker ? (
          <Explained label={t('i12w.act.record')} why={notBuilt} />
        ) : (
          <Locked label={t('i12w.act.record')} why={t('i12w.why.record')} />
        )}
        <Explained label={t('i12w.act.report')} why={notBuilt} />
        {checker ? (
          <Explained label={t('i12w.act.release')} kind="pri" why={notBuilt} />
        ) : (
          <Locked label={t('i12w.act.release')} why={t('i12w.why.release')} />
        )}
      </div>
      <p className="sub2" data-testid="i12-not-built">
        {notBuilt}
      </p>
      <KvCard
        title={t('i12w.member', [s?.eis ?? DASH, s?.name ?? DASH])}
        rows={[
          { k: t('i12w.detail'), v: '', section: true },
          { k: t('i12w.designation'), v: s?.designation ?? DASH, s: 'HRMS' },
          { k: t('i12w.category'), v: s?.category ?? DASH, s: 'HRMS' },
          { k: t('i12w.employeeType'), v: s ? t(`i12w.type.${s.employeeType}`) : DASH, s: 'HRMS' },
          { k: t('i12w.cpfCode'), v: s?.cpfCode ?? DASH, s: 'HRMS' },
          { k: t('i12w.staffCode'), v: s?.eis ?? DASH, s: 'SYS' },
          { k: t('i12w.posting'), v: s?.posting ?? DASH, s: 'HRMS' },
          { k: t('i12w.status'), v: s ? t(`i12w.emp.${s.employmentStatus}`) : DASH, s: 'SYS' },
          { k: t('i12w.sanction'), v: s?.sanctionNumber ?? DASH, s: 'SYS' },
          { k: t('i12w.payment'), v: '', section: true },
          { k: t('i12w.employee'), v: rupees(s?.employee), s: 'CALC' },
          { k: t('i12w.employer'), v: rupees(s?.employer), s: 'CALC' },
          { k: t('i12w.vpf'), v: rupees(s?.vpf), s: 'CALC' },
          { k: t('i12w.sanctioned'), v: rupees(s?.sanctioned), s: 'CALC' },
          {
            k: t('i12w.order'),
            v: s?.orderNumber ? `${s.orderNumber} · ${s.orderDate ? date(s.orderDate) : DASH}` : DASH,
            s: 'ENT',
          },
          { k: t('i12w.invoice'), v: s?.invoiceNumber ?? DASH, s: 'SYS' },
        ]}
      />
      <div className="fl-mt">
        <Panel
          title={t('i12w.exclusions')}
          items={EXCLUSIONS.map(([icon, n]) => ({
            icon,
            src: 'RULE' as const,
            tone: 'info' as const,
            title: t(`i12w.ex.${n}`),
            text: t('i12w.ex.m'),
          }))}
        />
      </div>
      <div className="fl-mt">
        <Tbl
          title={t('i12w.invoices')}
          cols={[
            { t: t('i12w.col.entry'), s: 'SYS' },
            { t: t('i12w.col.member'), s: 'HRMS' },
            { t: t('i12w.col.type'), s: 'SYS' },
            { t: t('i12w.col.amount'), s: 'CALC', num: true },
            { t: t('i12w.col.invoice'), s: 'SYS' },
            { t: t('i12w.col.status'), s: 'SYS' },
            { t: t('i12w.col.sent'), s: 'SYS' },
            { t: t('i12w.col.cancel'), s: 'INTF' },
            { t: t('i12w.col.action'), s: 'SYS' },
          ]}
          after={
            <div className="bd">
              <div className="oq">{t('i12w.cancelNote')}</div>
            </div>
          }
        >
          <ServerPagination
            queryKey={cpfScreenKeys.supplementary}
            fetchPage={cpfScreenPages.supplementary}
            empty={t('i12w.none')}
            onRows={onRows}
          >
            {(rows) =>
              rows.map((r) => (
                <tr
                  key={r.entry}
                  aria-selected={s?.entry === r.entry}
                  className={s?.entry === r.entry ? 'fl-row-selected' : undefined}
                  onClick={() => setSelected(r)}
                >
                  <td>
                    <b>{r.entry}</b>
                  </td>
                  <td>
                    {r.eis} {r.name}
                  </td>
                  <td>{t('i12w.typeValue')}</td>
                  <td className="num">{amount(r.sanctioned)}</td>
                  <td>{r.invoiceNumber ?? DASH}</td>
                  <td>{r.invoiceStatus ? t(`i12w.inv.${r.invoiceStatus}`) : t('i12w.inv.NONE')}</td>
                  <td>{r.sentToApps ? date(r.sentToApps) : DASH}</td>
                  <td>{t(`i12w.apps.${r.appsCancelStatus ?? 'NOT_REQUESTED'}`)}</td>
                  <td>
                    <button
                      type="button"
                      className="ia off d"
                      disabled
                      title={t('i12w.why.cancel')}
                      data-why={t('i12w.why.cancel')}
                      aria-label={t('i12w.cancelName', [r.entry])}
                    >
                      {t('wf.cancel')}
                    </button>
                  </td>
                </tr>
              ))
            }
          </ServerPagination>
        </Tbl>
      </div>
    </>
  );
}
