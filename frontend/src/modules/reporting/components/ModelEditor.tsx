'use client';

import { useI18n } from '@/shared/i18n';
import { Src, TextAreaField } from '@/shared/ui';
import type {
  ModelControl,
  ModelDistribution,
  ModelField,
  ModelParameter,
  VersionProposal,
} from '../api/reportApi';
import { BINDINGS, CHANNELS, CONSUMERS, RECONCILES } from '../business/reportRules';

/**
 * M-12 ▸ Report Definition Model of a new version (SRS-18-9, BR-MIS-001; M-12 field table
 * L3368-L3378; US-GAP-34): the parameter set (each parameter with its validation rule), the field
 * bindings to the component catalogue (pay code) or the field-source model (entity.field), the
 * rounding / presentation key, the control totals and the distribution routing. The server
 * rejects a binding that does not resolve.
 */
export function ModelEditor({
  form,
  setForm,
}: {
  form: VersionProposal;
  setForm: (next: VersionProposal) => void;
}) {
  const { t } = useI18n();
  const parameters = form.parameters ?? [];
  const fields = form.fields ?? [];
  const controls = form.controls ?? [];
  const distribution = form.distribution ?? [];

  function at<T>(list: T[], i: number, next: T): T[] {
    return list.map((x, j) => (j === i ? next : x));
  }
  function without<T>(list: T[], i: number): T[] {
    return list.filter((_, j) => j !== i);
  }
  const setParameters = (next: ModelParameter[]) => setForm({ ...form, parameters: next });
  const setFields = (next: ModelField[]) => setForm({ ...form, fields: next });
  const setControls = (next: ModelControl[]) => setForm({ ...form, controls: next });
  const setDistribution = (next: ModelDistribution[]) => setForm({ ...form, distribution: next });

  return (
    <div className="m12-model">
      <label>
        {t('m12.rounding')}
        <Src s="ENT" />{' '}
        <input
          maxLength={100}
          value={form.roundingRuleKey ?? ''}
          onChange={(e) =>
            setForm({ ...form, roundingRuleKey: e.target.value === '' ? null : e.target.value.toUpperCase() })
          }
        />
      </label>

      <h5>{t('m12.parameters')}</h5>
      {parameters.map((p, i) => (
        <div className="toolbar" key={`p${i}`} data-row="parameter">
          <label>
            {t('m12.parameterCode')}{' '}
            <input
              maxLength={40}
              value={p.code}
              onChange={(e) => setParameters(at(parameters, i, { ...p, code: e.target.value }))}
            />
          </label>
          <label>
            {t('m12.dataType')}{' '}
            <input
              maxLength={20}
              value={p.dataType}
              onChange={(e) =>
                setParameters(at(parameters, i, { ...p, dataType: e.target.value.toUpperCase() }))
              }
            />
          </label>
          <label>
            {t('m12.validationRule')}{' '}
            <TextAreaField
              maxLength={500}
              value={p.validationRule}
              onChange={(e) => setParameters(at(parameters, i, { ...p, validationRule: e.target.value }))}
            />
          </label>
          <label>
            <input
              type="checkbox"
              checked={p.mandatory}
              onChange={(e) => setParameters(at(parameters, i, { ...p, mandatory: e.target.checked }))}
            />{' '}
            {t('m12.mandatory')}
          </label>
          <button type="button" className="ia" onClick={() => setParameters(without(parameters, i))}>
            {t('m12.remove')}
          </button>
        </div>
      ))}
      <button
        type="button"
        className="btn"
        onClick={() =>
          setParameters([
            ...parameters,
            {
              code: '',
              label: null,
              dataType: '',
              mandatory: true,
              roleScoped: false,
              defaultExpression: null,
              validationRule: '',
            },
          ])
        }
      >
        {t('m12.addParameter')}
      </button>

      <h5>{t('m12.fields')}</h5>
      {fields.map((f, i) => (
        <div className="toolbar" key={`f${i}`} data-row="field">
          <label>
            {t('m12.fieldLabel')}{' '}
            <input
              maxLength={200}
              value={f.label}
              onChange={(e) => setFields(at(fields, i, { ...f, label: e.target.value }))}
            />
          </label>
          <label>
            {t('m12.binding')}{' '}
            <select
              value={f.binding}
              onChange={(e) =>
                setFields(at(fields, i, { ...f, binding: e.target.value as ModelField['binding'] }))
              }
            >
              {BINDINGS.map((b) => (
                <option key={b} value={b}>
                  {t(`m12.binding.${b}`)}
                </option>
              ))}
            </select>
          </label>
          <label>
            {t('m12.reference')}{' '}
            <TextAreaField
              maxLength={1000}
              value={f.reference}
              onChange={(e) => setFields(at(fields, i, { ...f, reference: e.target.value }))}
            />
          </label>
          <button type="button" className="ia" onClick={() => setFields(without(fields, i))}>
            {t('m12.remove')}
          </button>
        </div>
      ))}
      <button
        type="button"
        className="btn"
        onClick={() =>
          setFields([...fields, { label: '', binding: 'COMPONENT', reference: '', formatMask: null }])
        }
      >
        {t('m12.addField')}
      </button>

      <h5>{t('m12.controls')}</h5>
      {controls.map((c, i) => (
        <div className="toolbar" key={`c${i}`} data-row="control">
          <label>
            {t('m12.controlLabel')}{' '}
            <input
              maxLength={200}
              value={c.label}
              onChange={(e) => setControls(at(controls, i, { ...c, label: e.target.value }))}
            />
          </label>
          <label>
            {t('m12.reconcilesAgainst')}{' '}
            <select
              value={c.reconcilesAgainst}
              onChange={(e) => setControls(at(controls, i, { ...c, reconcilesAgainst: e.target.value }))}
            >
              {RECONCILES.map((r) => (
                <option key={r} value={r}>
                  {t(`m12.reconciles.${r}`)}
                </option>
              ))}
            </select>
          </label>
          <label>
            {t('m12.tolerance')}{' '}
            <input
              inputMode="decimal"
              value={c.tolerance}
              onChange={(e) => setControls(at(controls, i, { ...c, tolerance: e.target.value }))}
            />
          </label>
          <label>
            <input
              type="checkbox"
              checked={c.releaseGate}
              onChange={(e) => setControls(at(controls, i, { ...c, releaseGate: e.target.checked }))}
            />{' '}
            {t('m12.releaseGate')}
          </label>
          <button type="button" className="ia" onClick={() => setControls(without(controls, i))}>
            {t('m12.remove')}
          </button>
        </div>
      ))}
      <button
        type="button"
        className="btn"
        onClick={() =>
          setControls([
            ...controls,
            { label: '', reconcilesAgainst: 'POSTED_PAYROLL', tolerance: '0', releaseGate: true },
          ])
        }
      >
        {t('m12.addControl')}
      </button>

      <h5>{t('m12.distribution')}</h5>
      {distribution.map((d, i) => (
        <div className="toolbar" key={`d${i}`} data-row="distribution">
          <label>
            {t('m12.consumer')}{' '}
            <input
              maxLength={200}
              value={d.consumerName}
              onChange={(e) => setDistribution(at(distribution, i, { ...d, consumerName: e.target.value }))}
            />
          </label>
          <label>
            {t('m12.consumerType')}{' '}
            <select
              value={d.consumerType}
              onChange={(e) => setDistribution(at(distribution, i, { ...d, consumerType: e.target.value }))}
            >
              {CONSUMERS.map((x) => (
                <option key={x} value={x}>
                  {t(`m12.consumer.${x}`)}
                </option>
              ))}
            </select>
          </label>
          <label>
            {t('m12.channel')}{' '}
            <select
              value={d.channel}
              onChange={(e) => setDistribution(at(distribution, i, { ...d, channel: e.target.value }))}
            >
              {CHANNELS.map((x) => (
                <option key={x} value={x}>
                  {t(`m12.channel.${x}`)}
                </option>
              ))}
            </select>
          </label>
          <button type="button" className="ia" onClick={() => setDistribution(without(distribution, i))}>
            {t('m12.remove')}
          </button>
        </div>
      ))}
      <button
        type="button"
        className="btn"
        onClick={() =>
          setDistribution([
            ...distribution,
            { consumerName: '', consumerType: 'INTERNAL_ROLE', channel: 'SCREEN', schedule: null },
          ])
        }
      >
        {t('m12.addConsumer')}
      </button>
    </div>
  );
}
