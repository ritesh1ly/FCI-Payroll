'use client';

import { useState } from 'react';
import { ApiError } from '@/shared/api';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import {
  amount,
  Card,
  date,
  dateTime,
  Empty,
  Problem,
  ReasonDialog,
  ServerPagination,
  Src,
  useToast,
} from '@/shared/ui';
import type { Sanction, SanctionStage, TierDecision } from '../api/cpfApi';
import { STAGE_PILL, tierAction, waitingTier } from '../business/sanctionRules';
import {
  cpfKeys,
  sanctionDocumentUrl,
  NO_CASTE_DISPUTE,
  sanctionPages,
  useAttachUnitCertificate,
  useSanction,
  useTierAction,
} from '../orchestration/cpfQueries';

const STAGES: SanctionStage[] = [
  'UNIT_VERIFICATION',
  'ZONAL_HQ_VALIDATION',
  'COMPETENT_AUTHORITY',
  'SANCTIONED',
  'PAYMENT_BLOCKED',
  // SRS l.2278: Workflow Stage includes Payment Released.
  'PAID',
  'RETURNED',
  'REJECTED',
  'CANCELLED',
];

/** The reason dialog of each decision: every one carries its reason (a tier-2 rejection too). */
const DIALOG: Record<TierDecision, { title: string; confirm: string }> = {
  FORWARD: { title: 'i04.recommendTitle', confirm: 'i04.recommend' },
  RETURN: { title: 'i04.returnTitle', confirm: 'i04.return' },
  REJECT: { title: 'i04.rejectTitle', confirm: 'i04.reject' },
};

/**
 * I-04 Loans, Advances & CPF Sanctions - the CPF withdrawal register and its tiers (SRS-6-4,
 * UC-05; SRS-6-26, SRS-6-55; plan 07 D-14): the Unit CPF Administrator verifies (tier 1), the
 * CPF Division recommends at the capped amount (tier 2); the Competent Authority sanctions with OTP
 * in the Approvals Inbox (tier 3). A return always carries its reason; tier 2 may also reject a
 * request that fails its validation, with the reason the member sees (FRS V0.5 §13.4 Step 3).
 */
export function CpfSanctions({ opening = null }: { opening?: number | null }) {
  const { t } = useI18n();
  const toast = useToast();
  const me = useMe();
  const [stage, setStage] = useState('');
  const [openId, setOpenId] = useState<number | null>(opening);
  // The reason dialog: every return, and the tier-2 recommendation (its remark is the reason of
  // the I04_SUBMIT approval, WF-400-REASON below 10 characters).
  const [asking, setAsking] = useState<{ s: Sanction; decision: TierDecision } | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [dialogError, setDialogError] = useState<unknown>(null);
  const open = useSanction(openId);
  const act = useTierAction();
  const may = (action: string) => can(me.data, 'I-04', action);

  async function decide(s: Sanction, decision: TierDecision, remark: string) {
    const step = tierAction(s.stage, may);
    if (!step) return;
    setError(null);
    setDialogError(null);
    try {
      const after = await act.mutateAsync({ id: s.id, step, decision, remark });
      setAsking(null);
      toast(t('i04.done', [s.id, t(`i04.stage.${after.stage}`)]));
    } catch (cause) {
      if (cause instanceof ApiError && cause.messageKey === 'otp.cancelled') {
        setAsking(null);
        return;
      }
      // UI audit S46: a refusal keeps the reason dialog (and the typed reason) open with the error.
      if (remark !== '' || asking !== null) {
        setDialogError(cause);
        return;
      }
      setError(cause);
    }
  }

  const s = open.data;
  const step = s ? tierAction(s.stage, may) : null;
  const certificateMissing =
    s !== undefined &&
    step === 'verify' &&
    s.noCasteDisputeRequired &&
    !s.documents.some((d) => d.documentTypeCode === NO_CASTE_DISPUTE);
  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <label className="fl-filter">
          {t('i04.stage')}{' '}
          <select value={stage} onChange={(e) => setStage(e.target.value)}>
            <option value="">{t('common.all')}</option>
            {STAGES.map((x) => (
              <option key={x} value={x}>
                {t(`i04.stage.${x}`)}
              </option>
            ))}
          </select>
        </label>
        <span className="sub2">{t('i04.hint')}</span>
      </div>
      <Problem error={error} />
      <Card title={t('i04.register')}>
        <ServerPagination
          queryKey={cpfKeys.sanctionList(stage)}
          fetchPage={sanctionPages.list(stage)}
          empty={<Empty text={t('i04.none')} />}
        >
          {(rows) => (
            <table className="t" aria-label={t('i04.register')}>
              <thead>
                <tr>
                  <th>
                    {t('i04.no')}
                    <Src s="SYS" />
                  </th>
                  <th>
                    {t('m01.eis')}
                    <Src s="HRMS" />
                  </th>
                  <th>
                    {t('i04.type')}
                    <Src s="ESS" />
                  </th>
                  <th>
                    {t('i04.purpose')}
                    <Src s="ESS" />
                  </th>
                  <th>
                    {t('i04.requested')}
                    <Src s="ESS" />
                  </th>
                  <th>
                    {t('i04.sanctioned')}
                    <Src s="CALC" />
                  </th>
                  <th>
                    {t('i04.stage')}
                    <Src s="SYS" />
                  </th>
                  <th>
                    {t('i04.number')}
                    <Src s="SYS" />
                  </th>
                  <th aria-label={t('common.actions')} />
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.id} data-reference={String(r.id)}>
                    <td>{r.id}</td>
                    <td>
                      <b>{r.eis}</b>
                      <div className="sub2">{r.memberName}</div>
                    </td>
                    <td>{t(`i04.kind.${r.type}`)}</td>
                    <td>{r.purposeName ?? r.purposeCode ?? '—'}</td>
                    <td>{amount(r.requested)}</td>
                    <td>
                      {r.sanctioned === null ? '—' : amount(r.sanctioned)}
                      {r.capped && <span className="pill p-warn">{t('i04.capped')}</span>}
                    </td>
                    <td>
                      <span className={`pill ${STAGE_PILL[r.stage]}`}>{t(`i04.stage.${r.stage}`)}</span>
                    </td>
                    <td>{r.sanctionNumber ?? '—'}</td>
                    <td>
                      <button
                        type="button"
                        className="btn sm"
                        aria-label={t('i04.openOf', [r.id])}
                        onClick={() => setOpenId(r.id)}
                      >
                        {t('common.view')}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </ServerPagination>
      </Card>
      <Problem error={open.error} />
      {s && (
        <Card
          title={t('i04.requestOf', [s.id, s.memberName])}
          actions={
            <button type="button" className="btn sm" onClick={() => setOpenId(null)}>
              {t('common.close')}
            </button>
          }
        >
          <div className="bd">
            <div className="kv">
              {/* Tier 1 verifies the UAN and the KYC (SRS-6-4); the Aadhaar shows its last four digits only (SRS-6-51). */}
              <span className="k">
                {t('i04.uan')}
                <Src s="HRMS" />
              </span>
              <span>{s.uan ?? <span className="pill p-warn">{t('i04.notOnRecord')}</span>}</span>
              <span className="k">
                {t('i04.aadhaar')}
                <Src s="HRMS" />
              </span>
              <span>{s.aadhaarMasked ?? <span className="pill p-warn">{t('i04.notOnRecord')}</span>}</span>
              <span className="k">{t('i04.purpose')}</span>
              <span>
                {s.purposeName ?? '—'} ({t(`i04.kind.${s.type}`)})
              </span>
              <span className="k">{t('i04.requested')}</span>
              <span>{amount(s.requested)}</span>
              <span className="k">{t('i04.ceiling')}</span>
              <span>{s.ceiling === null ? '—' : amount(s.ceiling)}</span>
              <span className="k">{t('i04.sanctioned')}</span>
              <span>
                {s.sanctioned === null ? '—' : amount(s.sanctioned)}
                {s.capped && <span className="pill p-warn">{t('i04.capped')}</span>}
              </span>
              <span className="k">{t('i04.stage')}</span>
              <span className={`pill ${STAGE_PILL[s.stage]}`}>{t(`i04.stage.${s.stage}`)}</span>
              <span className="k">{t('i04.number')}</span>
              <span>
                {s.sanctionNumber ?? '—'} {s.sanctionDate ? `· ${date(s.sanctionDate)}` : ''}
              </span>
              {s.stage === 'CANCELLED' && (
                <>
                  {/* SRS-G-37: a cancelled request keeps its reason. */}
                  <span className="k">{t('i04.cancelReason')}</span>
                  <span>{s.cancelReason ?? '—'}</span>
                </>
              )}
              {s.earlierWithdrawals && (
                <>
                  {/* SRS l.2279 Withdrawal Count: the register's other withdrawals that count (BR-CPF-010). */}
                  <span className="k">
                    {t('i04.withdrawalCount')}
                    <Src s="SYS" />
                  </span>
                  <span>
                    {s.earlierWithdrawals.length === 0 ? (
                      t('i04.noEarlier')
                    ) : (
                      <>
                        {s.earlierWithdrawals.length}
                        <ul className="fl-list" aria-label={t('i04.withdrawalCount')}>
                          {s.earlierWithdrawals.map((w) => (
                            <li key={w.id}>
                              {t(`i04.kind.${w.type}`)}
                              {w.purposeCode ? ` · ${w.purposeCode}` : ''} · {t(`i04.stage.${w.stage}`)}
                              {w.sanctionDate ? ` · ${date(w.sanctionDate)}` : ''}
                              {w.sanctionNumber ? ` · ${w.sanctionNumber}` : ''}
                            </li>
                          ))}
                        </ul>
                      </>
                    )}
                  </span>
                </>
              )}
              {s.propertyAddress && (
                <>
                  <span className="k">{t('e01s.property')}</span>
                  <span>
                    {s.propertyAddress} — {s.propertyDescription}
                  </span>
                </>
              )}
            </div>
            {waitingTier(s.stage) === 3 && <p className="fl-hint">{t('i04.tier3Hint')}</p>}
            {s.stage === 'PAYMENT_BLOCKED' && <p className="fl-hint">{t('i04.blockedHint')}</p>}
          </div>
          <table className="t" aria-label={t('i04.tiers')}>
            <thead>
              <tr>
                <th>
                  {t('i04.pass')}
                  <Src s="SYS" />
                </th>
                <th>
                  {t('i04.tier')}
                  <Src s="SYS" />
                </th>
                <th>
                  {t('i04.action')}
                  <Src s="SYS" />
                </th>
                <th>
                  {t('i04.by')}
                  <Src s="SYS" />
                </th>
                <th>
                  {t('i04.at')}
                  <Src s="SYS" />
                </th>
                <th>
                  {t('common.reason')}
                  <Src s="ENT" />
                </th>
              </tr>
            </thead>
            <tbody>
              {s.tiers.map((x) => (
                <tr key={`${x.pass}-${x.tier}`}>
                  <td>{x.pass}</td>
                  <td>{t(`i04.tierCode.${x.tierCode}`)}</td>
                  <td>{t(`i04.tierAction.${x.action}`)}</td>
                  <td>{x.actedBy}</td>
                  <td>{dateTime(x.actedAt)}</td>
                  <td>{x.remark ?? '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <div className="bd">
            <h4>{t('i04.documents')}</h4>
            {s.documents.length === 0 ? (
              <p className="fl-hint">{t('i04.noDocuments')}</p>
            ) : (
              <ul className="fl-list">
                {s.documents.map((d) => (
                  <li key={d.id}>
                    <a href={sanctionDocumentUrl(s.id, d.id)}>{d.fileName}</a> · {d.documentTypeName}
                    {d.referenceNumber ? ` · ${d.referenceNumber}` : ''}
                    {d.issuingAuthority ? ` · ${d.issuingAuthority}` : ''}
                  </li>
                ))}
              </ul>
            )}
            {certificateMissing && <UnitCertificateForm id={s.id} />}
            {step && (
              <div className="rf-actbar">
                <button
                  type="button"
                  className="btn"
                  disabled={act.isPending}
                  onClick={() => setAsking({ s, decision: 'RETURN' })}
                >
                  {t(step === 'verify' ? 'i04.returnToMember' : 'i04.returnToUnit')}
                </button>
                {step === 'recommend' && (
                  <button
                    type="button"
                    className="btn"
                    disabled={act.isPending}
                    onClick={() => setAsking({ s, decision: 'REJECT' })}
                  >
                    {t('i04.reject')}
                  </button>
                )}
                <button
                  type="button"
                  className="btn pri"
                  // UI audit S13 (SRS-6-14): tier 1 forwards only with the No Caste Dispute
                  // Certificate attached when the purpose needs it; the server refuses it otherwise.
                  disabled={act.isPending || certificateMissing}
                  title={certificateMissing ? t('i04.ncd.forwardWhy') : undefined}
                  onClick={() =>
                    step === 'verify' ? void decide(s, 'FORWARD', '') : setAsking({ s, decision: 'FORWARD' })
                  }
                >
                  {t(step === 'verify' ? 'i04.forward' : 'i04.recommend')}
                </button>
              </div>
            )}
          </div>
        </Card>
      )}
      {asking && (
        <ReasonDialog
          title={t(DIALOG[asking.decision].title, [asking.s.id])}
          confirmLabel={t(DIALOG[asking.decision].confirm)}
          onConfirm={(why) => void decide(asking.s, asking.decision, why)}
          onCancel={() => {
            setAsking(null);
            setDialogError(null);
          }}
          error={dialogError}
          busy={act.isPending}
        />
      )}
    </div>
  );
}

/**
 * SRS-6-14 (US-GAP-28): the Unit CPF Administrator attaches the No Caste Dispute Certificate the
 * purpose requires; the request cannot be forwarded without it.
 */
function UnitCertificateForm({ id }: { id: number }) {
  const { t } = useI18n();
  const toast = useToast();
  const attach = useAttachUnitCertificate();
  const [documentDate, setDocumentDate] = useState('');
  const [issuingAuthority, setIssuingAuthority] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const ready = documentDate !== '' && issuingAuthority.trim() !== '' && file !== null && !attach.isPending;

  async function onAttach() {
    if (!file) return;
    try {
      await attach.mutateAsync({
        id,
        certificate: { documentDate, issuingAuthority: issuingAuthority.trim(), file },
      });
      toast(t('i04.ncd.attached'));
    } catch {
      // shown by <Problem error={attach.error} />
    }
  }

  return (
    <fieldset className="rf-grid" aria-label={t('i04.ncd.title')}>
      <legend>{t('i04.ncd.title')}</legend>
      <p className="fl-hint">{t('i04.ncd.hint')}</p>
      <label className="fl-field">
        {t('i04.ncd.date')}
        <input type="date" value={documentDate} onChange={(e) => setDocumentDate(e.target.value)} />
      </label>
      <label className="fl-field">
        {t('i04.ncd.authority')}
        <input
          maxLength={200}
          value={issuingAuthority}
          onChange={(e) => setIssuingAuthority(e.target.value)}
        />
      </label>
      <label className="fl-field">
        {t('i04.ncd.file')}
        <input
          type="file"
          accept=".pdf,.jpg,.jpeg,.png,application/pdf,image/jpeg,image/png"
          onChange={(e) => setFile(e.target.files?.[0] ?? null)}
        />
      </label>
      <Problem error={attach.error} />
      <button type="button" className="btn" disabled={!ready} onClick={() => void onAttach()}>
        {t('i04.ncd.attach')}
      </button>
    </fieldset>
  );
}
