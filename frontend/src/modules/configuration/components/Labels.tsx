'use client';

import { useCallback, useState } from 'react';
import { HistoryButton } from '@/modules/audit';
import { ApiError } from '@/shared/api';
import { useI18n } from '@/shared/i18n';
import { ReferenceOptions } from '@/shared/reference';
import { can, useMe } from '@/shared/session';
import {
  Card,
  date,
  ExportButton,
  Problem,
  ReasonDialog,
  ServerPagination,
  TextAreaField,
  useToast,
  withBase,
} from '@/shared/ui';
import { Act, Explained, Locked, Panel, RowActs, Tbl, ViewDialog } from '@/shared/ui/wireframe';
import { businessToday } from '@/shared/time';
import type { LabelView, ProposedLabel, LabelQuery } from '../api/mdmApi';
import { LABEL_SCOPES, LABEL_TYPES } from '../business/mdmRules';
import {
  mdmKeys,
  mdmLinks,
  mdmPages,
  useProposeLabel,
  useUploadLabels,
  useWithdrawLabel,
} from '../orchestration/mdmQueries';

const EMPTY: Omit<ProposedLabel, 'reason'> = {
  key: '',
  type: 'UI_LABEL',
  masterEntity: null,
  masterCode: null,
  language: 'HI',
  scope: 'GLOBAL',
  text: '',
  localisable: false,
  effectiveFrom: '',
};

const STATUS_PILL: Record<string, string> = {
  ACTIVE: 'p-ok',
  PENDING_APPROVAL: 'p-warn',
  SUPERSEDED: 'p-gray',
  WITHDRAWN: 'p-bad',
  REJECTED: 'p-bad',
};

/**
 * M-26 Label Master as Wireframes v6.1 draws it (a frozen requirement per OD-121; SRS-G-39, SRS
 * l.974): "🕘 History", "⬇ Bulk download", "⬆ Bulk upload" (Administrator) and "📄 Missing
 * translations" over the screen, the Labels card with Export, one row per key, language and scope,
 * and the Display rules panel. A proposed local name reads "Proposed — RO to approve"; on Missing
 * translations (Active English Global labels with no Active Hindi text) each row is the Hindi row
 * that is missing, status "Hindi pending". Headquarters proposes Global labels; a region or office
 * proposes a local name for a localisable master value (the row's Edit, WF-084), approved by its
 * Regional Office; Headquarters withdraws a local name from the record's View. A new label by hand
 * opens with the bulk upload panel; the search closes under its disclosure (WF-082).
 */
export function Labels() {
  const { t } = useI18n();
  const toast = useToast();
  const me = useMe();
  const [text, setText] = useState('');
  const [language, setLanguage] = useState('');
  const [scope, setScope] = useState('');
  const [missing, setMissing] = useState(false);
  const listQuery: LabelQuery = {
    text: text.trim() || undefined,
    language: language || undefined,
    scope: scope || undefined,
  };
  const propose = useProposeLabel();
  const withdraw = useWithdrawLabel();
  const upload = useUploadLabels();
  const [selected, setSelected] = useState<LabelView | null>(null);
  const [viewing, setViewing] = useState<LabelView | null>(null);
  const [bulkOpen, setBulkOpen] = useState(false);
  const [draft, setDraft] = useState<Omit<ProposedLabel, 'reason'> | null>(null);
  const [reason, setReason] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [uploadReason, setUploadReason] = useState('');
  const [withdrawing, setWithdrawing] = useState<LabelView | null>(null);
  const [error, setError] = useState<unknown>(null);
  const mayCreate = can(me.data, 'M-26', 'CREATE');
  const mayWithdraw = can(me.data, 'M-26', 'EDIT');
  const mayApprove = can(me.data, 'M-26', 'APPROVE');
  const mayExport = can(me.data, 'M-26', 'EXPORT');
  const shown = useCallback((page: LabelView[]) => {
    setSelected((prev) => page.find((l) => l.id === prev?.id) ?? page[0] ?? null);
  }, []);

  function startNew(from?: LabelView, language?: string) {
    setDraft({
      ...EMPTY,
      effectiveFrom: businessToday(),
      ...(from
        ? {
            key: from.key,
            type: from.type,
            masterEntity: from.masterEntity,
            masterCode: from.masterCode,
            language: language ?? from.language,
            // An office proposes a local name; a Global label stays with Headquarters (SRS-G-39).
            scope: !mayWithdraw && from.scope === 'GLOBAL' ? 'OFFICE' : from.scope,
            localisable: from.localisable,
          }
        : {}),
    });
    setReason('');
    setError(null);
  }

  async function submit() {
    if (!draft) return;
    setError(null);
    try {
      await propose.mutateAsync({ ...draft, reason });
      toast(t('m26.submitted', [draft.key]));
      setDraft(null);
    } catch (cause) {
      if (cause instanceof ApiError && cause.messageKey === 'otp.cancelled') return;
      setError(cause);
    }
  }

  async function submitUpload() {
    if (!file) return;
    setError(null);
    try {
      const rows = await upload.mutateAsync({ file, reason: uploadReason });
      toast(t('m26.uploaded', [rows.length]));
      setFile(null);
      setUploadReason('');
    } catch (cause) {
      setError(cause);
    }
  }

  async function confirmWithdraw(why: string) {
    if (!withdrawing) return;
    try {
      await withdraw.mutateAsync({ id: withdrawing.id, reason: why });
      toast(t('m26.withdrawSubmitted', [withdrawing.key]));
    } catch (cause) {
      if (!(cause instanceof ApiError && cause.messageKey === 'otp.cancelled')) setError(cause);
    } finally {
      setWithdrawing(null);
    }
  }

  /** "Proposed — RO to approve" is a local name awaiting its Regional Office (SRS l.986). */
  function statusOf(l: LabelView): string {
    if (missing) return t('m26.status.HINDI_PENDING');
    if (l.status === 'PENDING_APPROVAL' && l.scope !== 'GLOBAL') return t('m26.status.PROPOSED');
    return t(`m26.status.${l.status}`);
  }

  const scopeOf = (l: LabelView) =>
    l.scopeOffice ? t('m26.scopeAt', [t(`m26.scope.${l.scope}`), l.scopeOffice]) : t(`m26.scope.${l.scope}`);

  /** The Administrator revises any Active label; an office only a localisable master value. */
  const editable = (l: LabelView) =>
    mayCreate &&
    !draft &&
    l.status === 'ACTIVE' &&
    (mayWithdraw || (l.type === 'MASTER_VALUE' && l.localisable));

  function editWhy(l: LabelView): string {
    if (!mayCreate) return t('wf.why.adminMaintained');
    if (l.status !== 'ACTIVE') return t('m26.why.notActive');
    return !mayWithdraw && !(l.type === 'MASTER_VALUE' && l.localisable)
      ? t('wf.why.adminMaintained')
      : t('m26.why.formOpen');
  }

  const masterValue = draft?.type === 'MASTER_VALUE';

  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sp" />
        {selected ? (
          <HistoryButton
            entity="label_master"
            recordKey={String(selected.id)}
            label={selected.key}
            text={t('wf.historyAct')}
            className="btn"
          />
        ) : (
          <Act label={t('wf.historyAct')} onClick={() => toast(t('wf.selectRow'))} />
        )}
        {mayExport ? (
          <ExportButton
            href={mdmLinks.labelsExport(language || undefined, scope || undefined)}
            what={t('screen.M-26')}
            label={t('m26.act.download')}
          />
        ) : (
          <Explained label={t('m26.act.download')} why={t('m26.why.export')} />
        )}
        {mayWithdraw ? (
          <Act label={t('m26.act.upload')} kind="pri" onClick={() => setBulkOpen(!bulkOpen)} />
        ) : (
          <Locked label={t('m26.act.upload')} why={t('m26.why.upload')} />
        )}
        <Act label={t('m26.act.missing')} onClick={() => setMissing(!missing)} />
      </div>
      <Problem error={draft ? null : error} />
      <details>
        <summary className="sub2">{t('wf.searchDisclosure')}</summary>
        <div className="toolbar fl-filters">
          <label htmlFor="lb-text" className="sub2">
            {t('m26.search')}
          </label>
          <input id="lb-text" maxLength={64} value={text} onChange={(e) => setText(e.target.value)} />
          <label htmlFor="lb-lang" className="sub2">
            {t('m26.language')}
          </label>
          <select id="lb-lang" value={language} onChange={(e) => setLanguage(e.target.value)}>
            <option value="">{t('common.all')}</option>
            <ReferenceOptions list="LANGUAGE" />
          </select>
          <label htmlFor="lb-scope" className="sub2">
            {t('m26.scope')}
          </label>
          <select id="lb-scope" value={scope} onChange={(e) => setScope(e.target.value)}>
            <option value="">{t('common.all')}</option>
            {LABEL_SCOPES.map((s) => (
              <option key={s} value={s}>
                {t(`m26.scope.${s}`)}
              </option>
            ))}
          </select>
        </div>
      </details>
      <Tbl
        title={t('m26.table')}
        acts={
          mayExport ? (
            <ExportButton
              href={mdmLinks.labelsExport(language || undefined, scope || undefined)}
              what={t('screen.M-26')}
              className="btn sm"
            />
          ) : (
            <Explained label={t('action.export')} kind="sm" why={t('m26.why.export')} />
          )
        }
        cols={[
          { t: t('m26.col.key'), s: 'ENT' },
          { t: t('m26.col.type'), s: 'ENT' },
          { t: t('m26.col.language'), s: 'ENT' },
          { t: t('m26.col.scope'), s: 'ENT' },
          { t: t('m26.col.text'), s: 'ENT' },
          { t: t('m26.col.localisable'), s: 'ENT' },
          { t: t('m26.col.effective'), s: 'ENT' },
          { t: t('wf.col.status'), s: 'SYS' },
          { t: t('wf.col.actions'), s: 'SYS' },
        ]}
        after={
          missing ? (
            <div className="bd fl-pad-top-0">
              <div className="oq">{t('m26.missingNote')}</div>
            </div>
          ) : undefined
        }
      >
        <ServerPagination
          queryKey={missing ? mdmKeys.missingHindi : mdmKeys.labels(listQuery)}
          fetchPage={missing ? mdmPages.missingHindi : mdmPages.labels(listQuery)}
          personal={false}
          empty={<p className="fl-hint">{missing ? t('m26.noneMissing') : t('m26.none')}</p>}
          onRows={shown}
        >
          {(rows) =>
            rows.map((l) => (
              <tr
                key={l.id}
                data-key={l.key}
                aria-selected={selected?.id === l.id}
                className={selected?.id === l.id ? 'fl-row-selected' : undefined}
                onClick={() => setSelected(l)}
              >
                <td>
                  <b>{l.key}</b>
                </td>
                <td>{t(`m26.type.${l.type}`)}</td>
                <td>{missing ? 'HI' : l.language}</td>
                <td>{scopeOf(l)}</td>
                <td lang={l.language === 'HI' ? 'hi' : undefined}>{missing ? '—' : l.text}</td>
                <td>{l.localisable ? 'Y' : 'N'}</td>
                <td>
                  {missing ? '—' : date(l.effectiveFrom)}
                  {!missing && l.effectiveTo && (
                    <div className="sub2">{t('m26.until', [date(l.effectiveTo)])}</div>
                  )}
                </td>
                <td>
                  <span className={`pill ${missing ? 'p-bad' : (STATUS_PILL[l.status] ?? '')}`}>
                    {statusOf(l)}
                  </span>
                </td>
                <td onClick={(e) => e.stopPropagation()}>
                  <RowActs
                    viewName={`${t('common.view')} ${l.key}`}
                    editName={`${t('common.edit')} ${l.key}`}
                    onView={() => setViewing(l)}
                    onEdit={editable(l) ? () => startNew(l, missing ? 'HI' : undefined) : undefined}
                    editWhy={editWhy(l)}
                    onApprove={
                      mayApprove
                        ? () =>
                            l.status === 'PENDING_APPROVAL'
                              ? window.location.assign(withBase('/payroll/?s=W-01'))
                              : toast(t('wf.nothingPending'))
                        : undefined
                    }
                  />
                </td>
              </tr>
            ))
          }
        </ServerPagination>
      </Tbl>
      <div className="fl-mt">
        <Panel
          title={t('m26.panel.title')}
          items={[
            {
              icon: '🌐',
              tone: 'info',
              title: t('m26.panel.blank'),
              src: 'RULE',
              text: t('m26.panel.blankSub'),
            },
            {
              icon: '🔀',
              tone: 'info',
              title: t('m26.panel.cross'),
              src: 'RULE',
              text: t('m26.panel.crossSub'),
            },
            {
              icon: '#',
              tone: 'gray',
              title: t('m26.panel.code'),
              src: 'RULE',
              text: t('m26.panel.codeSub'),
            },
          ]}
        />
      </div>
      {viewing && (
        <ViewDialog
          screen={t('screen.M-26')}
          onClose={() => setViewing(null)}
          rows={[
            { k: t('m26.col.key'), s: 'ENT', v: viewing.key },
            { k: t('m26.col.type'), s: 'ENT', v: t(`m26.type.${viewing.type}`) },
            { k: t('m26.masterEntity'), s: 'ENT', v: viewing.masterEntity ?? '—' },
            { k: t('m26.masterCode'), s: 'ENT', v: viewing.masterCode ?? '—' },
            { k: t('m26.col.language'), s: 'ENT', v: viewing.language },
            { k: t('m26.col.scope'), s: 'ENT', v: scopeOf(viewing) },
            { k: t('m26.col.text'), s: 'ENT', v: viewing.text },
            { k: t('m26.col.localisable'), s: 'ENT', v: viewing.localisable ? 'Y' : 'N' },
            { k: t('mst.from'), s: 'ENT', v: date(viewing.effectiveFrom) },
            { k: t('mst.to'), s: 'ENT', v: viewing.effectiveTo ? date(viewing.effectiveTo) : '—' },
            { k: t('mst.version'), s: 'SYS', v: `v${viewing.versionNo}` },
            { k: t('wf.col.status'), s: 'SYS', v: statusOf(viewing) },
          ]}
          actions={
            mayWithdraw &&
            viewing.status === 'ACTIVE' &&
            viewing.scope !== 'GLOBAL' && (
              <Act
                label={t('action.withdraw')}
                onClick={() => {
                  setViewing(null);
                  setWithdrawing(viewing);
                }}
              />
            )
          }
        />
      )}
      {draft && (
        <Card title={t('action.newLabel')}>
          <Problem error={error} />
          <div className="bd rf-grid">
            <div className="rf-field span2">
              <label htmlFor="nl-key">{t('m26.key')}</label>
              <input
                id="nl-key"
                maxLength={100}
                value={draft.key}
                onChange={(e) => setDraft({ ...draft, key: e.target.value })}
              />
            </div>
            <div className="rf-field">
              <label htmlFor="nl-type">{t('m26.typeLabel')}</label>
              <select
                id="nl-type"
                value={draft.type}
                onChange={(e) => setDraft({ ...draft, type: e.target.value, scope: 'GLOBAL' })}
              >
                {LABEL_TYPES.map((x) => (
                  <option key={x} value={x}>
                    {t(`m26.type.${x}`)}
                  </option>
                ))}
              </select>
            </div>
            <div className="rf-field">
              <label htmlFor="nl-lang">{t('m26.language')}</label>
              <select
                id="nl-lang"
                value={draft.language}
                onChange={(e) => setDraft({ ...draft, language: e.target.value })}
              >
                <ReferenceOptions list="LANGUAGE" />
              </select>
            </div>
            {masterValue && (
              <>
                <div className="rf-field">
                  <label htmlFor="nl-entity">{t('m26.masterEntity')}</label>
                  <input
                    id="nl-entity"
                    maxLength={60}
                    value={draft.masterEntity ?? ''}
                    onChange={(e) => setDraft({ ...draft, masterEntity: e.target.value || null })}
                  />
                </div>
                <div className="rf-field">
                  <label htmlFor="nl-code">{t('m26.masterCode')}</label>
                  <input
                    id="nl-code"
                    maxLength={40}
                    value={draft.masterCode ?? ''}
                    onChange={(e) => setDraft({ ...draft, masterCode: e.target.value || null })}
                  />
                </div>
              </>
            )}
            <div className="rf-field">
              <label htmlFor="nl-scope">{t('m26.scope')}</label>
              <select
                id="nl-scope"
                value={draft.scope}
                disabled={!masterValue}
                onChange={(e) => setDraft({ ...draft, scope: e.target.value })}
              >
                {LABEL_SCOPES.map((s) => (
                  <option key={s} value={s}>
                    {t(`m26.scope.${s}`)}
                  </option>
                ))}
              </select>
              {!masterValue && <span className="hint">{t('m26.scopeHint')}</span>}
            </div>
            {masterValue && draft.scope === 'GLOBAL' && (
              <div className="rf-field">
                <label htmlFor="nl-local">
                  <input
                    id="nl-local"
                    type="checkbox"
                    checked={draft.localisable}
                    onChange={(e) => setDraft({ ...draft, localisable: e.target.checked })}
                  />{' '}
                  {t('m26.localisable')}
                </label>
              </div>
            )}
            <div className="rf-field span2">
              <label htmlFor="nl-text">{t('m26.text')}</label>
              <TextAreaField
                id="nl-text"
                // OPEN-QUESTION: CLR-79 - SRS l.983 gives 500, DDL v4.0 label_text NVARCHAR2(1000)
                maxLength={500}
                asciiOnly={false}
                lang={draft.language === 'HI' ? 'hi' : undefined}
                value={draft.text}
                onChange={(e) => setDraft({ ...draft, text: e.target.value })}
              />
            </div>
            <div className="rf-field">
              <label htmlFor="nl-from">{t('mst.from')}</label>
              <input
                id="nl-from"
                type="date"
                value={draft.effectiveFrom}
                onChange={(e) => setDraft({ ...draft, effectiveFrom: e.target.value })}
              />
            </div>
            <div className="rf-field span2">
              <label htmlFor="nl-reason">{t('common.reason')}</label>
              <TextAreaField
                id="nl-reason"
                value={reason}
                maxLength={500}
                onChange={(e) => setReason(e.target.value)}
              />
            </div>
          </div>
          <p className="fl-hint">{draft.scope === 'GLOBAL' ? t('m26.globalNote') : t('m26.localNote')}</p>
          <div className="rf-actbar">
            <button
              type="button"
              className="btn"
              onClick={() => {
                setDraft(null);
                setError(null);
              }}
            >
              {t('common.cancel')}
            </button>
            <button
              type="button"
              className="btn pri"
              disabled={
                !reason.trim() ||
                !draft.key.trim() ||
                !draft.text.trim() ||
                !draft.effectiveFrom ||
                propose.isPending
              }
              onClick={() => void submit()}
            >
              {t('common.submitForApproval')}
            </button>
          </div>
        </Card>
      )}
      {bulkOpen && (
        <Card title={t('m26.bulkUpload')}>
          <p className="sub2">{t('m26.bulkHint')}</p>
          <div className="bd rf-grid">
            <div className="rf-field">
              <label htmlFor="lb-file">{t('m26.file')}</label>
              <input
                id="lb-file"
                type="file"
                accept=".csv,text/csv"
                onChange={(e) => setFile(e.target.files?.[0] ?? null)}
              />
            </div>
            <div className="rf-field span2">
              <label htmlFor="lb-reason">{t('common.reason')}</label>
              <TextAreaField
                id="lb-reason"
                value={uploadReason}
                maxLength={500}
                onChange={(e) => setUploadReason(e.target.value)}
              />
            </div>
          </div>
          <div className="rf-actbar">
            {mayCreate && !draft && (
              <button type="button" className="btn" onClick={() => startNew()}>
                {t('action.newLabel')}
              </button>
            )}
            <button
              type="button"
              className="btn pri"
              disabled={!file || !uploadReason.trim() || upload.isPending}
              onClick={() => void submitUpload()}
            >
              {t('m26.upload')}
            </button>
          </div>
        </Card>
      )}
      {withdrawing && (
        <ReasonDialog
          title={t('m26.withdrawTitle', [withdrawing.key])}
          confirmLabel={t('action.withdraw')}
          onConfirm={(why) => void confirmWithdraw(why)}
          onCancel={() => setWithdrawing(null)}
        />
      )}
    </div>
  );
}
