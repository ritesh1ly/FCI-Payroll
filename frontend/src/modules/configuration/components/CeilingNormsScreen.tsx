'use client';

import { useRef, useState } from 'react';
import { ApiError } from '@/shared/api';
import { useI18n } from '@/shared/i18n';
import { ReferenceOptions, useReference } from '@/shared/reference';
import { businessToday } from '@/shared/time';
import { amount, date, Problem, ServerPagination, useToast } from '@/shared/ui';
import {
  Act,
  Explained,
  KvCard,
  Locked,
  Panel,
  RowActs,
  Tbl,
  Tiles,
  ViewDialog,
  WfTile,
} from '@/shared/ui/wireframe';
import type { NormRow, NormVersion, ProposedNorm } from '../api/mdmApi';
import { MEMBER_SCOPES } from '../business/mdmRules';
import {
  mdmKeys,
  mdmPages,
  useCeilingBreaches,
  useCurrentCirculars,
  useNormCounts,
  useProposeNorm,
} from '../orchestration/mdmQueries';
import { CeilingNorms } from './CeilingNorms';
import { RegisterSection, SimulateAct, useApproveRow, useMasterRights } from './MasterScreenParts';

type Draft = Omit<ProposedNorm, 'reason'> & { claimType: string; reason: string };

function emptyDraft(claimType = ''): Draft {
  return {
    claimType,
    effectiveFrom: businessToday(),
    ceilingBasis: null,
    ceilingValue: null,
    periodicity: '',
    eligibilityCategory: '',
    memberScope: 'BOTH',
    confirmed: false,
    circularOrderId: null,
    reason: '',
  };
}

/** Whether a CEILING_BASIS code is a percentage basis, as the pack and the M-15 tile test it. */
export function percentageBasis(basis: string | null | undefined): boolean {
  return (basis ?? '').toUpperCase().includes('PERCENT');
}

/**
 * M-15 Allowance & Reimbursement Ceiling Norms as Wireframes v6.1 draws it (OD-121; SRS-16-14, SRS-11-15,
 * SRS l.1397-1423; DDL allowance_ceiling_norm): the tiles, the standing New Rule card, one row per claim
 * type with each field in its own column, the Entitlement rules and where each field is consumed. The
 * Rule Administrator proposes and the Checker approves on W-01; the drawn Administrator (a System
 * Administrator) sees the entry as drawn, told why it cannot use it (OD-135: no grant to match a drawing).
 * The version history, the row New Version and the member scope stay in the closed register. Ceiling Value's
 * 100 % and ₹ 5,00,000 caps are the pack's, shown as a hint, not a rule of the SRS (WF-1401). Claim Type, Periodicity
 * and Eligibility Category read their M-16 lists (SRS l.1418-1422, OD-123).
 */
export function CeilingNormsScreen() {
  const { t } = useI18n();
  const toast = useToast();
  const rights = useMasterRights('M-15');
  const approve = useApproveRow();
  const reference = useReference();
  const counts = useNormCounts();
  const breaches = useCeilingBreaches();
  const circulars = useCurrentCirculars();
  const propose = useProposeNorm();
  const [register, setRegister] = useState(false);
  const [draft, setDraft] = useState<Draft>(() => emptyDraft());
  const [error, setError] = useState<unknown>(null);
  const [viewing, setViewing] = useState<NormRow | null>(null);
  const form = useRef<HTMLDivElement | null>(null);

  function focusForm(claimType?: string) {
    if (claimType) setDraft(emptyDraft(claimType));
    form.current?.scrollIntoView?.({ behavior: 'smooth' });
  }

  const missing =
    draft.claimType === '' ||
    draft.ceilingBasis === null ||
    draft.ceilingValue === null ||
    !(draft.ceilingValue > 0) ||
    draft.periodicity === '' ||
    draft.eligibilityCategory === '' ||
    draft.effectiveFrom === '' ||
    draft.reason.trim() === '';

  async function submit() {
    setError(null);
    const { claimType, ...body } = draft;
    try {
      await propose.mutateAsync({ claimType, body: { ...body, reason: draft.reason.trim() } });
      toast(t('mst.submitted', [reference.label('CLAIM_TYPE', claimType) || claimType]));
      setDraft(emptyDraft());
    } catch (cause) {
      if (cause instanceof ApiError && cause.messageKey === 'otp.cancelled') return;
      setError(cause);
    }
  }

  function valueText(v: NormVersion): string {
    return percentageBasis(v.ceilingBasis) ? `${v.ceilingValue}%` : amount(v.ceilingValue);
  }

  /** What the row shows for a field: the version in force, else the proposal waiting. */
  const shown = (row: NormRow) => row.current ?? row.pending;

  function statusPill(row: NormRow) {
    if (row.pending) return <span className="pill p-warn">{t('status.PENDING_APPROVAL')}</span>;
    if (!row.current) return <span className="pill p-bad">{t('mst.notConfigured')}</span>;
    return row.current.confirmed ? (
      <span className="pill p-ok">{t('status.ACTIVE')}</span>
    ) : (
      <span className="pill p-warn">{t('mst.unconfirmed')}</span>
    );
  }

  const hint =
    draft.ceilingBasis === null
      ? t('m15w.valueHint')
      : t(percentageBasis(draft.ceilingBasis) ? 'm15w.valueHintPercent' : 'm15w.valueHintAmount');

  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sp" />
        <Act label={t('wf.historyAct')} onClick={() => setRegister(true)} />
        <SimulateAct label={t('m15w.simulate')} />
        {rights.drawn ? (
          <Act label={t('m15w.newRule')} kind="pri" onClick={() => focusForm()} />
        ) : (
          <Locked label={t('m15w.newRule')} kind="pri" why={t('m15w.why.admin')} />
        )}
      </div>
      <Tiles>
        <WfTile
          icon="🧳"
          label={t('m15w.tile.types')}
          src="RULE"
          big={counts.data?.claimTypes ?? '—'}
          sub={t('m15w.tile.typesSub')}
        />
        <WfTile
          icon="％"
          tone="pur"
          label={t('m15w.tile.percent')}
          src="RULE"
          big={counts.data?.percentageRules ?? '—'}
          sub={t('m15w.tile.percentSub')}
        />
        <WfTile
          icon="✍️"
          tone="warn"
          label={t('m15w.tile.pending')}
          src="SYS"
          big={counts.data?.pendingApproval ?? '—'}
          sub={t('m15w.tile.pendingSub')}
        />
        <WfTile
          icon="⛔"
          tone="bad"
          bad
          label={t('m15w.tile.breaches')}
          src="CALC"
          big={breaches.data ?? '—'}
          sub={t('m15w.tile.breachesSub')}
        />
      </Tiles>
      <div className="card" ref={form} data-testid="m15-form">
        <div className="hd">
          <h3>{t('m15w.formTitle')}</h3>
        </div>
        <div className="bd">
          {!rights.drawn ? (
            <p className="sub2">{t('m15w.formNote')}</p>
          ) : (
            <>
              <p className="sub2">{t('m15w.formAdminNote')}</p>
              <Problem error={error} />
              <div className="rf-grid">
                <div className="rf-field">
                  <label htmlFor="ac-claim">
                    {t('m15w.claimType')} <span className="req">*</span>
                  </label>
                  <select
                    id="ac-claim"
                    value={draft.claimType}
                    onChange={(e) => setDraft({ ...draft, claimType: e.target.value })}
                  >
                    <option value="">{t('m15w.select')}</option>
                    <ReferenceOptions list="CLAIM_TYPE" />
                  </select>
                </div>
                <div className="rf-field">
                  <label htmlFor="ac-basis">
                    {t('m15w.basis')} <span className="req">*</span>
                  </label>
                  <select
                    id="ac-basis"
                    value={draft.ceilingBasis ?? ''}
                    onChange={(e) => setDraft({ ...draft, ceilingBasis: e.target.value || null })}
                  >
                    <option value="">{t('m15w.select')}</option>
                    <ReferenceOptions list="CEILING_BASIS" />
                  </select>
                </div>
                <div className="rf-field">
                  <label htmlFor="ac-value">
                    {t('m15w.value')} <span className="req">*</span>
                  </label>
                  <input
                    id="ac-value"
                    type="number"
                    min={0}
                    step="0.5"
                    placeholder={t('m15w.valuePlaceholder')}
                    value={draft.ceilingValue ?? ''}
                    onChange={(e) =>
                      setDraft({
                        ...draft,
                        ceilingValue: e.target.value === '' ? null : Number(e.target.value),
                      })
                    }
                  />
                  <span className="hint">{hint}</span>
                </div>
                <div className="rf-field">
                  <label htmlFor="ac-period">
                    {t('m15w.periodicity')} <span className="req">*</span>
                  </label>
                  <select
                    id="ac-period"
                    value={draft.periodicity}
                    onChange={(e) => setDraft({ ...draft, periodicity: e.target.value })}
                  >
                    <option value="">{t('m15w.select')}</option>
                    <ReferenceOptions list="ALLOWANCE_PERIODICITY" />
                  </select>
                </div>
                <div className="rf-field">
                  <label htmlFor="ac-elig">
                    {t('m15w.eligibility')} <span className="req">*</span>
                  </label>
                  <select
                    id="ac-elig"
                    value={draft.eligibilityCategory}
                    onChange={(e) => setDraft({ ...draft, eligibilityCategory: e.target.value })}
                  >
                    <option value="">{t('m15w.select')}</option>
                    <ReferenceOptions list="ELIGIBILITY_CATEGORY" />
                  </select>
                </div>
                <div className="rf-field">
                  <label htmlFor="ac-eff">
                    {t('m15w.effectiveFrom')} <span className="req">*</span>
                  </label>
                  <input
                    id="ac-eff"
                    type="date"
                    value={draft.effectiveFrom}
                    onChange={(e) => setDraft({ ...draft, effectiveFrom: e.target.value })}
                  />
                </div>
                <div className="rf-field span2">
                  <label htmlFor="ac-reason">
                    {t('m15w.reason')} <span className="req">*</span>
                  </label>
                  <input
                    id="ac-reason"
                    type="text"
                    maxLength={500}
                    placeholder={t('m15w.reasonPlaceholder')}
                    value={draft.reason}
                    onChange={(e) => setDraft({ ...draft, reason: e.target.value })}
                  />
                </div>
              </div>
              {/* Not drawn: the member scope (DDL member_scope) and the M-25 circular (SRS-G-38), WF-1402. */}
              <details className="fl-mt" data-testid="m15-form-more">
                <summary>{t('m15w.more')}</summary>
                <div className="rf-grid">
                  <div className="rf-field">
                    <label htmlFor="ac-scope">{t('m15.memberScope')}</label>
                    <select
                      id="ac-scope"
                      value={draft.memberScope}
                      onChange={(e) => setDraft({ ...draft, memberScope: e.target.value })}
                    >
                      {MEMBER_SCOPES.map((s) => (
                        <option key={s} value={s}>
                          {t(`m15.memberScope.${s}`)}
                        </option>
                      ))}
                    </select>
                  </div>
                  <div className="rf-field">
                    <label htmlFor="ac-conf">
                      <input
                        id="ac-conf"
                        type="checkbox"
                        checked={draft.confirmed}
                        onChange={(e) => setDraft({ ...draft, confirmed: e.target.checked })}
                      />{' '}
                      {t('mst.confirmed')}
                    </label>
                    <span className="hint">{t('mst.confirmedHint')}</span>
                  </div>
                  <div className="rf-field span2">
                    <label htmlFor="ac-circular">{t('mst.circular')}</label>
                    <select
                      id="ac-circular"
                      value={draft.circularOrderId ?? ''}
                      onChange={(e) =>
                        setDraft({
                          ...draft,
                          circularOrderId: e.target.value === '' ? null : Number(e.target.value),
                        })
                      }
                    >
                      <option value="">{t('common.choose')}</option>
                      {(circulars.data ?? []).map((c) => (
                        <option key={c.id} value={c.id}>
                          {c.number} · {c.subject}
                        </option>
                      ))}
                    </select>
                    {(circulars.data ?? []).length === 0 && (
                      <span className="hint">{t('m27.noActiveCircular')}</span>
                    )}
                  </div>
                </div>
              </details>
              <div className="rf-actbar">
                {rights.entryWhy !== null ? (
                  <Explained label={t('m15w.save')} kind="pri" why={rights.entryWhy} />
                ) : draft.circularOrderId === null ? (
                  <Locked label={t('m15w.save')} kind="pri" why={t('mst.why.circular')} />
                ) : (
                  <button
                    type="button"
                    className="btn pri"
                    disabled={missing || propose.isPending}
                    title={missing ? t('m15w.why.incomplete') : undefined}
                    onClick={() => void submit()}
                  >
                    {t('m15w.save')}
                  </button>
                )}
              </div>
            </>
          )}
        </div>
      </div>
      <Tbl
        title={t('screen.M-15')}
        acts={
          <>
            <SimulateAct label={t('m15w.simulateSm')} />
            {rights.drawn ? (
              <Act label={t('m20.supersede')} kind="sm" onClick={() => focusForm()} />
            ) : (
              <Explained label={t('m20.supersede')} kind="sm" why={t('m15w.why.admin')} />
            )}
          </>
        }
        cols={[
          { t: t('m15w.claimType'), s: 'ENT' },
          { t: t('m15w.basis'), s: 'RULE' },
          { t: t('m15w.value'), s: 'RULE', num: false },
          { t: t('m15w.periodicity'), s: 'RULE' },
          { t: t('m15w.eligibility'), s: 'RULE' },
          { t: t('m15w.effFrom'), s: 'ENT' },
          { t: t('i03.status'), s: 'SYS' },
          { t: t('m04.col.actions'), s: 'SYS' },
        ]}
      >
        <ServerPagination queryKey={[...mdmKeys.norms, '#drawn']} fetchPage={mdmPages.norms} personal={false}>
          {(rows) =>
            rows.map((row) => {
              const v = shown(row);
              const name = reference.label('CLAIM_TYPE', row.claimType) || row.name;
              return (
                <tr key={row.claimType} data-reference={row.claimType}>
                  <td>
                    <b>{name}</b>
                  </td>
                  <td>{v?.ceilingBasis ? reference.label('CEILING_BASIS', v.ceilingBasis) : '—'}</td>
                  <td>{v ? valueText(v) : '—'}</td>
                  <td>{v ? t(`m15.periodicity.${v.periodicity}`) : '—'}</td>
                  <td>{v ? reference.label('ELIGIBILITY_CATEGORY', v.eligibilityCategory) : '—'}</td>
                  <td>{v ? date(v.effectiveFrom) : '—'}</td>
                  <td>{statusPill(row)}</td>
                  <td>
                    <RowActs
                      viewName={`${t('common.view')} ${name}`}
                      editName={`${t('common.edit')} ${name}`}
                      onView={() => setViewing(row)}
                      onEdit={rights.drawn && !row.pending ? () => focusForm(row.claimType) : undefined}
                      editWhy={rights.drawn ? t('mst.why.pending') : t('m15w.why.admin')}
                      onApprove={rights.checker ? () => approve(row.pending !== null) : undefined}
                    />
                  </td>
                </tr>
              );
            })
          }
        </ServerPagination>
      </Tbl>
      <div className="g2 fl-mt">
        <Panel
          title={t('m15w.rules')}
          items={[
            { icon: '✈️', tone: 'info', title: t('wf.rule.ltc'), src: 'RULE', text: t('wf.rule.ltcSub') },
            {
              icon: '🩺',
              tone: 'info',
              title: t('wf.rule.quarterly'),
              src: 'RULE',
              text: t('wf.rule.quarterlySub'),
            },
            {
              icon: '🏥',
              tone: 'info',
              title: t('wf.rule.indoor'),
              src: 'RULE',
              text: t('wf.rule.indoorSub'),
            },
            {
              icon: '🏠',
              tone: 'info',
              title: t('wf.rule.lease'),
              src: 'RULE',
              text: t('wf.rule.leaseSub'),
            },
            {
              icon: '⛔',
              tone: 'bad',
              title: t('wf.rule.overlap'),
              src: 'RULE',
              text: t('wf.rule.overlapSub'),
              right: <span className="pill p-bad">{t('wf.rule.enforced')}</span>,
            },
          ]}
        />
        <KvCard
          title={t('m15w.consumed')}
          rows={[
            { k: t('m15w.consumed.valueKey'), v: t('m15w.consumed.value'), s: 'CALC' },
            { k: t('m15w.periodicity'), v: t('m15w.consumed.periodicity'), s: 'RULE' },
            { k: t('m15w.eligibility'), v: t('m15w.consumed.eligibility'), s: 'RULE' },
          ]}
        />
      </div>
      <RegisterSection
        id="m15-register"
        label={t('mst.register.M-15')}
        open={register}
        onToggle={setRegister}
      >
        {register && <CeilingNorms />}
      </RegisterSection>
      {viewing && (
        <ViewDialog
          screen={t('screen.M-15')}
          onClose={() => setViewing(null)}
          rows={[
            {
              k: t('m15w.claimType'),
              v: reference.label('CLAIM_TYPE', viewing.claimType) || viewing.name,
              s: 'ENT',
            },
            {
              k: t('m15w.basis'),
              v: shown(viewing)?.ceilingBasis
                ? reference.label('CEILING_BASIS', shown(viewing)?.ceilingBasis ?? '')
                : '—',
              s: 'RULE',
            },
            {
              k: t('m15w.value'),
              v: shown(viewing) ? valueText(shown(viewing) as NormVersion) : '—',
              s: 'RULE',
            },
            {
              k: t('m15w.periodicity'),
              v: shown(viewing) ? t(`m15.periodicity.${shown(viewing)?.periodicity}`) : '—',
              s: 'RULE',
            },
            {
              k: t('m15w.eligibility'),
              v: shown(viewing)
                ? reference.label('ELIGIBILITY_CATEGORY', shown(viewing)?.eligibilityCategory ?? '')
                : '—',
              s: 'RULE',
            },
            {
              k: t('m15.memberScope'),
              v: shown(viewing) ? t(`m15.memberScope.${shown(viewing)?.memberScope}`) : '—',
              s: 'RULE',
            },
            {
              k: t('m15w.effFrom'),
              v: shown(viewing) ? date(shown(viewing)?.effectiveFrom) : '—',
              s: 'ENT',
            },
            { k: t('i03.status'), v: statusPill(viewing), s: 'SYS' },
          ]}
        />
      )}
    </div>
  );
}
