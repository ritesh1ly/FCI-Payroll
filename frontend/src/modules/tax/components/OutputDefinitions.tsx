'use client';

import { useState } from 'react';
import { HistoryButton } from '@/modules/audit';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { Card, date, Empty, Problem, Src, useToast, TextAreaField } from '@/shared/ui';
import type {
  Binding,
  ControlTotal,
  LayoutFormat,
  OutputField,
  ReconcilesAgainst,
  StatutoryOutput,
  VersionProposal,
} from '../api/statutoryApi';
import { latestFrom, versionProblems } from '../business/statutoryRules';
import { useOutputs, useProposeVersion } from '../orchestration/statutoryQueries';

const BINDINGS: readonly Binding[] = ['COMPONENT', 'FIELD_SOURCE', 'AGGREGATE', 'DERIVED', 'LITERAL'];
const LAYOUTS: readonly LayoutFormat[] = ['PDF', 'EXCEL', 'CSV', 'PRESCRIBED_FILE', 'HTML'];
const AGAINST: readonly ReconcilesAgainst[] = [
  'POSTED_PAYROLL',
  'GL_POSTING_STATEMENT',
  'BANK_ADVICE',
  'CHALLAN',
];

function blankVersion(): VersionProposal {
  return {
    effectiveFrom: '',
    layoutFormat: 'PDF',
    layoutNote: '',
    roundingRuleKey: '',
    periodLockRequired: true,
    fields: [{ label: '', binding: 'COMPONENT', reference: '', formatMask: null }],
    controls: [],
    reason: '',
  };
}

/**
 * M-17 Statutory Output Definitions (SRS M-17, BR-TAX-013, SRS-7-5, SRS-7-8, SRS-7-30). The
 * statutory outputs the SRS names, each BLOCKED until a version is Active: the Rule Administrator at
 * Headquarters proposes an effective-dated version (fields bound to the component catalogue or the
 * field-source model, layout, rounding, control totals, period lock); a different Rule Approver
 * approves it in the Approvals Inbox. Earlier periods keep their version; nothing is deleted.
 */
export function OutputDefinitions() {
  const { t } = useI18n();
  const outputs = useOutputs();
  const [chosen, setChosen] = useState<string | null>(null);
  if (outputs.isPending) return <p className="fl-hint">{t('common.loading')}</p>;
  if (!outputs.data) return <Problem error={outputs.error} />;
  const current = outputs.data.find((o) => o.key === chosen) ?? null;
  return (
    <div className="wireframe-body-container">
      <Card title={t('screen.M-17')}>
        <p className="sub2">{t('m17.hint')}</p>
        <table className="t" aria-label={t('screen.M-17')}>
          <thead>
            <tr>
              <th>
                {t('m17.output')}
                <Src s="SYS" />
              </th>
              <th>
                {t('m17.screen')}
                <Src s="SYS" />
              </th>
              <th>
                {t('m17.reference')}
                <Src s="SYS" />
              </th>
              <th>
                {t('m17.state')}
                <Src s="SYS" />
              </th>
              <th>
                {t('m17.versions')}
                <Src s="SYS" />
              </th>
              <th aria-label={t('common.actions')} />
            </tr>
          </thead>
          <tbody>
            {outputs.data.map((o) => (
              <tr key={o.key} data-reference={o.key}>
                <td>
                  {o.name}
                  <div className="sub2">{o.key}</div>
                </td>
                <td>{o.producingScreen ?? '—'}</td>
                <td>{o.statutoryReference ?? '—'}</td>
                <td>
                  <span className={`pill ${o.state === 'ACTIVE' ? 'p-ok' : 'p-warn'}`}>
                    {t(`m17.state.${o.state}`)}
                  </span>
                  {o.pendingApprovals.length > 0 && (
                    <span className="pill p-info"> {t('status.PENDING_APPROVAL')}</span>
                  )}
                </td>
                <td>{o.versions.length}</td>
                <td>
                  <button
                    type="button"
                    className="ia"
                    aria-label={t('m17.openOf', [o.key])}
                    onClick={() => setChosen(o.key)}
                  >
                    {t('common.view')}
                  </button>
                  <HistoryButton entity="report_definition_version" recordKey={o.key} label={o.name} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>
      {current && <Output key={current.key} output={current} onClose={() => setChosen(null)} />}
    </div>
  );
}

function Output({ output, onClose }: { output: StatutoryOutput; onClose: () => void }) {
  const { t } = useI18n();
  const me = useMe();
  const mayPropose = can(me.data, 'M-17', 'CREATE');
  return (
    <Card title={t('m17.outputOf', [output.name, output.key])}>
      {output.versions.length === 0 ? (
        <Empty text={t('m17.blocked')} />
      ) : (
        output.versions.map((v) => (
          <div className="bd" key={v.id}>
            <h3>
              {t('m17.versionOf', [
                v.number,
                date(v.effectiveFrom),
                v.effectiveTo ? date(v.effectiveTo) : t('m17.open'),
              ])}{' '}
              {v.inForce && <span className="pill p-ok">{t('m17.inForce')}</span>}
            </h3>
            <p className="sub2">
              {t(`m17.layout.${v.layoutFormat}`)} ·{' '}
              {t(v.periodLockRequired ? 'm17.lockRequired' : 'm17.noLock')}
            </p>
            <table className="t" aria-label={t('m17.fieldsOf', [v.number])}>
              <thead>
                <tr>
                  <th>
                    {t('m17.label')}
                    <Src s="RULE" />
                  </th>
                  <th>
                    {t('m17.binding')}
                    <Src s="RULE" />
                  </th>
                  <th>
                    {t('m17.ref')}
                    <Src s="RULE" />
                  </th>
                  <th>
                    {t('m17.mask')}
                    <Src s="RULE" />
                  </th>
                </tr>
              </thead>
              <tbody>
                {v.fields.map((f) => (
                  <tr key={f.label}>
                    <td>{f.label}</td>
                    <td>{t(`m17.binding.${f.binding}`)}</td>
                    <td>{f.reference}</td>
                    <td>{f.formatMask ?? '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            {v.controls.length > 0 && (
              <ul className="fl-list" aria-label={t('m17.controlsOf', [v.number])}>
                {v.controls.map((c) => (
                  <li key={c.label}>
                    {t('m17.controlLine', [
                      c.label,
                      t(`m17.against.${c.reconcilesAgainst}`),
                      String(c.tolerance),
                    ])}
                    {c.releaseGate && <span className="pill p-info"> {t('m17.releaseGate')}</span>}
                  </li>
                ))}
              </ul>
            )}
          </div>
        ))
      )}
      {mayPropose &&
        (output.pendingApprovals.length > 0 ? (
          <p className="fl-hint">{t('m17.waiting', [output.pendingApprovals.join(', ')])}</p>
        ) : (
          <NewVersion output={output} />
        ))}
      <div className="rf-actbar">
        <button type="button" className="btn" onClick={onClose}>
          {t('common.close')}
        </button>
      </div>
    </Card>
  );
}

function NewVersion({ output }: { output: StatutoryOutput }) {
  const { t } = useI18n();
  const toast = useToast();
  const propose = useProposeVersion();
  const [draft, setDraft] = useState<VersionProposal>(blankVersion);
  const [error, setError] = useState<unknown>(null);
  const problems = versionProblems(draft, latestFrom(output.versions));
  const field = (i: number, change: Partial<OutputField>) =>
    setDraft((d) => ({ ...d, fields: d.fields.map((f, k) => (k === i ? { ...f, ...change } : f)) }));
  const control = (i: number, change: Partial<ControlTotal>) =>
    setDraft((d) => ({ ...d, controls: d.controls.map((c, k) => (k === i ? { ...c, ...change } : c)) }));

  async function submit() {
    setError(null);
    try {
      await propose.mutateAsync({ key: output.key, body: draft });
      toast(t('m17.proposed'));
      setDraft(blankVersion());
    } catch (cause) {
      setError(cause);
    }
  }

  return (
    <div className="bd">
      <h3>{t('m17.newVersion')}</h3>
      <Problem error={error} />
      <div className="rf-grid">
        <div className="rf-field">
          <label htmlFor="m17-from">{t('m17.effectiveFrom')}</label>
          <input
            id="m17-from"
            type="date"
            value={draft.effectiveFrom}
            onChange={(e) => setDraft({ ...draft, effectiveFrom: e.target.value })}
          />
        </div>
        <div className="rf-field">
          <label htmlFor="m17-layout">{t('m17.layoutFormat')}</label>
          <select
            id="m17-layout"
            value={draft.layoutFormat}
            onChange={(e) => setDraft({ ...draft, layoutFormat: e.target.value as LayoutFormat })}
          >
            {LAYOUTS.map((l) => (
              <option key={l} value={l}>
                {t(`m17.layout.${l}`)}
              </option>
            ))}
          </select>
        </div>
        <div className="rf-field">
          <label htmlFor="m17-rounding">{t('m17.rounding')}</label>
          <input
            id="m17-rounding"
            maxLength={100}
            value={draft.roundingRuleKey}
            onChange={(e) => setDraft({ ...draft, roundingRuleKey: e.target.value.toUpperCase() })}
          />
        </div>
        <div className="rf-field">
          <label>
            <input
              type="checkbox"
              checked={draft.periodLockRequired}
              onChange={(e) => setDraft({ ...draft, periodLockRequired: e.target.checked })}
            />{' '}
            {t('m17.lockRequired')}
          </label>
        </div>
        <div className="rf-field span2">
          <label htmlFor="m17-note">{t('m17.note')}</label>
          <TextAreaField
            id="m17-note"
            maxLength={4000}
            value={draft.layoutNote}
            onChange={(e) => setDraft({ ...draft, layoutNote: e.target.value })}
          />
        </div>
      </div>
      <table className="t" aria-label={t('m17.fields')}>
        <thead>
          <tr>
            <th>
              {t('m17.label')}
              <Src s="ENT" />
            </th>
            <th>
              {t('m17.binding')}
              <Src s="ENT" />
            </th>
            <th>
              {t('m17.ref')}
              <Src s="ENT" />
            </th>
            <th>
              {t('m17.mask')}
              <Src s="ENT" />
            </th>
            <th aria-label={t('common.actions')} />
          </tr>
        </thead>
        <tbody>
          {draft.fields.map((f, i) => (
            <tr key={i}>
              <td>
                <input
                  aria-label={t('m17.labelOf', [i + 1])}
                  maxLength={200}
                  value={f.label}
                  onChange={(e) => field(i, { label: e.target.value })}
                />
              </td>
              <td>
                <select
                  aria-label={t('m17.bindingOf', [i + 1])}
                  value={f.binding}
                  onChange={(e) => field(i, { binding: e.target.value as Binding })}
                >
                  {BINDINGS.map((b) => (
                    <option key={b} value={b}>
                      {t(`m17.binding.${b}`)}
                    </option>
                  ))}
                </select>
              </td>
              <td>
                <TextAreaField
                  aria-label={t('m17.refOf', [i + 1])}
                  rows={2}
                  maxLength={1000}
                  value={f.reference}
                  onChange={(e) => field(i, { reference: e.target.value })}
                />
              </td>
              <td>
                <input
                  aria-label={t('m17.maskOf', [i + 1])}
                  maxLength={60}
                  value={f.formatMask ?? ''}
                  onChange={(e) => field(i, { formatMask: e.target.value })}
                />
              </td>
              <td>
                <button
                  type="button"
                  className="ia"
                  aria-label={t('m17.removeField', [i + 1])}
                  disabled={draft.fields.length === 1}
                  onClick={() => setDraft({ ...draft, fields: draft.fields.filter((_, k) => k !== i) })}
                >
                  {t('common.remove')}
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <button
        type="button"
        className="btn"
        disabled={draft.fields.length >= 200}
        onClick={() =>
          setDraft({
            ...draft,
            fields: [...draft.fields, { label: '', binding: 'COMPONENT', reference: '', formatMask: null }],
          })
        }
      >
        {t('m17.addField')}
      </button>
      {draft.controls.map((c, i) => (
        <div className="rf-grid" key={i}>
          <div className="rf-field">
            <label htmlFor={`m17-cl-${i}`}>{t('m17.controlLabel')}</label>
            <input
              id={`m17-cl-${i}`}
              maxLength={200}
              value={c.label}
              onChange={(e) => control(i, { label: e.target.value })}
            />
          </div>
          <div className="rf-field">
            <label htmlFor={`m17-ca-${i}`}>{t('m17.against')}</label>
            <select
              id={`m17-ca-${i}`}
              value={c.reconcilesAgainst}
              onChange={(e) => control(i, { reconcilesAgainst: e.target.value as ReconcilesAgainst })}
            >
              {AGAINST.map((a) => (
                <option key={a} value={a}>
                  {t(`m17.against.${a}`)}
                </option>
              ))}
            </select>
          </div>
          <div className="rf-field">
            <label htmlFor={`m17-ct-${i}`}>{t('m17.tolerance')}</label>
            <input
              id={`m17-ct-${i}`}
              inputMode="decimal"
              value={String(c.tolerance)}
              onChange={(e) => control(i, { tolerance: e.target.value })}
            />
          </div>
          <div className="rf-field">
            <label>
              <input
                type="checkbox"
                checked={c.releaseGate}
                onChange={(e) => control(i, { releaseGate: e.target.checked })}
              />{' '}
              {t('m17.releaseGate')}
            </label>
          </div>
          {/* SRS M-17 l.2499: a control-total row added by mistake can be taken out again. */}
          <button
            type="button"
            className="btn"
            aria-label={t('m17.removeControl', [i + 1])}
            onClick={() => setDraft({ ...draft, controls: draft.controls.filter((_, k) => k !== i) })}
          >
            {t('common.remove')}
          </button>
        </div>
      ))}
      <button
        type="button"
        className="btn"
        disabled={draft.controls.length >= 20}
        onClick={() =>
          setDraft({
            ...draft,
            controls: [
              ...draft.controls,
              { label: '', reconcilesAgainst: 'POSTED_PAYROLL', tolerance: '0.00', releaseGate: true },
            ],
          })
        }
      >
        {t('m17.addControl')}
      </button>
      <div className="rf-field span2">
        <label htmlFor="m17-reason">{t('common.reason')}</label>
        <TextAreaField
          id="m17-reason"
          maxLength={1000}
          value={draft.reason}
          onChange={(e) => setDraft({ ...draft, reason: e.target.value })}
        />
      </div>
      {problems.length > 0 && (
        <ul className="fl-list" aria-label={t('common.problems')}>
          {problems.map((p) => (
            <li key={p}>{t(p)}</li>
          ))}
        </ul>
      )}
      <div className="rf-actbar">
        {/* DoD (UI review #74): a draft is discarded with Reset, as on the other masters. */}
        <button type="button" className="btn" onClick={() => setDraft(blankVersion())}>
          {t('common.reset')}
        </button>
        <button
          type="button"
          className="btn btn-primary"
          disabled={problems.length > 0 || propose.isPending}
          title={problems.length > 0 ? t('common.fixProblems') : undefined}
          onClick={() => void submit()}
        >
          {t('common.submitForApproval')}
        </button>
      </div>
    </div>
  );
}
