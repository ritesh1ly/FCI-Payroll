'use client';

import { useCallback, useMemo, useState } from 'react';
import { ApiError } from '@/shared/api';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import type { Decision, Item } from '../api/workflowApi';
import {
  inboxExportUrl,
  itemDocumentUrl,
  useBulkApprove,
  useDecision,
  useInboxSummary,
  useItemDocuments,
  workflowKeys,
  workflowPages,
} from '../orchestration/workflowQueries';
import {
  amount,
  dateTime,
  Empty,
  entityLabel,
  errorMessage,
  ExportButton,
  Problem,
  ReasonDialog,
  ServerPagination,
  useToast,
  withBase,
} from '@/shared/ui';
import { Act, Locked, RowActs, Tbl, Tiles, Toolbar, WfTile } from '@/shared/ui/wireframe';

/**
 * W-01 Approvals Inbox (X-02 of Wireframes v6.1, a frozen requirement per OD-121; SRS-G-29,
 * SRS-22-4, SRS-22-5, SRS-G-13): items routed by the approval workflow master to the role and
 * office of their current level within the session's scope. As drawn: "Return Selected" and
 * "Approve Selected" over the selection (a row is selected by clicking it), the four tiles over the
 * whole scope, the module and SLA filters with the list search and the export, and the Approval
 * Queue with Item, Module, Raised By, Value / Impact, Target and the row actions (View opens the
 * item's detail with its decision; Edit is drawn disabled, an item is decided, never edited; ✔ for
 * the role that may approve). A decision the session may not take is disabled with the precise
 * reason (maker = checker, HQ-only, other Region ...); the API enforces the same. Delegation is a
 * change of the routes on A-08 (BR-APPR-006), never an approver action. A role that may not approve
 * (the Maker, the Labour Dealing Hand, HR, Finance) sees its OWN submitted items in the same queue, as
 * the pack's RBAC strip says ("Maker ... see their own submitted items"): W-02 My Submissions folded in.
 * The Item cell carries the maker's description as its drawn sub-line (the pack's `.sub2` under the
 * item), so the Checker tells items of one record type apart in the queue and can search them.
 */
export function ApprovalInbox() {
  const { t } = useI18n();
  const toast = useToast();
  const me = useMe();
  const decideItem = useDecision();
  const bulk = useBulkApprove();
  const summary = useInboxSummary();
  const approver = can(me.data, 'W-01', 'APPROVE');
  const delegator = can(me.data, 'A-08', 'EDIT');
  const [module, setModule] = useState('');
  const [sla, setSla] = useState('');
  const [search, setSearch] = useState('');
  const [selected, setSelected] = useState<number[]>([]);
  const [shown, setShown] = useState<Item[]>([]);
  const [detail, setDetail] = useState<Item | null>(null);
  const [pending, setPending] = useState<{ item: Item; decision: Decision } | null>(null);
  const [bulkOpen, setBulkOpen] = useState(false);
  const [returnOpen, setReturnOpen] = useState(false);

  // UI_BMAD_g1 #92: only items on the page shown stay selected; another page, or a refresh after
  // another Checker decided one, drops the rest.
  const keepShown = useCallback((items: Item[]) => {
    setShown(items);
    setSelected((current) => {
      const kept = current.filter((id) => items.some((i) => i.approvalId === id));
      return kept.length === current.length ? current : kept;
    });
  }, []);

  const selectedItems = useMemo(
    () => shown.filter((i) => selected.includes(i.approvalId)),
    [shown, selected],
  );

  function toggle(item: Item) {
    if (!item.canDecide) return;
    setSelected((current) =>
      current.includes(item.approvalId)
        ? current.filter((id) => id !== item.approvalId)
        : [...current, item.approvalId],
    );
  }

  function visible(item: Item) {
    if (module && item.moduleCode !== module) return false;
    if (sla === 'BREACHED' && !item.slaBreached) return false;
    if (search.trim()) {
      const q = search.trim().toLowerCase();
      const text =
        `${item.approvalId} ${entityLabel(t, item.entityName)} ${item.entityKey} ${item.reason ?? ''} ${item.moduleCode ?? ''} ${item.submittedBy}`.toLowerCase();
      if (!text.includes(q)) return false;
    }
    return true;
  }

  function approveSelected() {
    if (selected.length === 0) {
      toast(t('w01.nothingSelected'));
      return;
    }
    const single = selectedItems.filter((i) => !i.bulkApproval).length;
    if (single > 0) {
      toast(t('w01.notBulkSelected', [single]));
      return;
    }
    setBulkOpen(true);
  }

  function returnSelected() {
    if (selected.length === 0) {
      toast(t('w01.nothingSelected'));
      return;
    }
    setReturnOpen(true);
  }

  async function runBulkApprove(reason: string) {
    try {
      const out = await bulk.mutateAsync({ approvalIds: selected, reason });
      setSelected([]);
      const refused = out.refused.map((r) =>
        t('w01.bulkRefused', [
          r.approvalId,
          r.reason === 'BULK_NOT_ALLOWED' ? t('w01.notBulk') : t(`error.${r.reason}`),
        ]),
      );
      toast(
        [t('w01.bulkDone', [out.reference, out.approved.length, out.refused.length]), ...refused].join(' '),
      );
    } catch (cause) {
      toast(errorMessage(t, cause));
    }
  }

  /** A return needs a reason (SRS-G-29) and is one decision per item: each is sent on its own. */
  async function runReturnSelected(reason: string) {
    let done = 0;
    let refused = 0;
    for (const item of selectedItems) {
      try {
        await decideItem.mutateAsync({ approvalId: item.approvalId, decision: 'RETURN', reason });
        done++;
      } catch (cause) {
        if (cause instanceof ApiError && cause.messageKey === 'otp.cancelled') break;
        refused++;
      }
    }
    setSelected([]);
    toast(t('w01.returnDone', [done, refused]));
  }

  async function decide(item: Item, choice: Decision, reason: string) {
    try {
      const result = await decideItem.mutateAsync({ approvalId: item.approvalId, decision: choice, reason });
      toast(t('w01.decided', [item.approvalId, t(`status.${result.status}`)]));
      setDetail(null);
    } catch (cause) {
      if (cause instanceof ApiError && cause.messageKey === 'otp.cancelled') return;
      toast(errorMessage(t, cause));
    }
  }

  const tiles = summary.data;
  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sp" />
        {approver ? (
          <Act label={t('w01.returnSelected')} onClick={returnSelected} />
        ) : (
          <Locked label={t('w01.returnSelected')} why={t('w01.why.approveSelected')} />
        )}
        {approver ? (
          <Act label={t('w01.approveSelected')} kind="pri" onClick={approveSelected} />
        ) : (
          <Locked label={t('w01.approveSelected')} why={t('w01.why.approveSelected')} />
        )}
      </div>
      <Problem error={summary.error} />
      <Tiles>
        <WfTile
          icon="✍️"
          label={t('w01.tile.pending')}
          src="SYS"
          big={tiles?.pending ?? '—'}
          sub={t('w01.tile.pendingSub', [tiles?.modules?.length ?? 0])}
          tone="warn"
        />
        <WfTile
          icon="⏰"
          label={t('w01.tile.pastTarget')}
          src="CALC"
          big={tiles?.pastTarget ?? '—'}
          sub={t('w01.tile.pastTargetSub')}
          tone="bad"
          bad={(tiles?.pastTarget ?? 0) > 0}
        />
        <WfTile
          icon="✅"
          label={t('w01.tile.approved')}
          src="SYS"
          big={tiles?.approvedThisCycle ?? '—'}
          sub={t('w01.tile.approvedSub')}
          tone="ok"
        />
        <WfTile
          icon="↩️"
          label={t('w01.tile.returned')}
          src="SYS"
          big={tiles?.returned ?? '—'}
          sub={t('w01.tile.returnedSub')}
          tone="pur"
        />
      </Tiles>
      <Toolbar
        search={search}
        onSearch={setSearch}
        exportControl={
          <ExportButton href={inboxExportUrl()} what={t('screen.W-01')} label={t('wf.export')} />
        }
      >
        <select
          aria-label={t('w01.filter.module')}
          value={module}
          onChange={(e) => setModule(e.target.value)}
        >
          <option value="">{t('w01.filter.allModules')}</option>
          {(tiles?.modules ?? []).map((m) => (
            <option key={m} value={m}>
              {m}
            </option>
          ))}
        </select>
        <select aria-label={t('w01.filter.sla')} value={sla} onChange={(e) => setSla(e.target.value)}>
          <option value="">{t('w01.filter.anySla')}</option>
          <option value="BREACHED">{t('w01.filter.breached')}</option>
        </select>
      </Toolbar>
      <Tbl
        title={t('w01.queue')}
        acts={
          delegator ? (
            <Act label={t('w01.delegate')} kind="sm" href={withBase('/payroll?s=A-08')} />
          ) : (
            <Locked label={t('w01.delegate')} kind="sm" why={t('w01.why.delegate')} />
          )
        }
        cols={[
          { t: t('w01.col.item'), s: 'SYS' },
          { t: t('w01.col.module'), s: 'SYS' },
          { t: t('w01.col.raisedBy'), s: 'SYS' },
          { t: t('w01.col.impact'), s: 'CALC' },
          { t: t('w01.col.target'), s: 'CALC' },
          { t: t('w01.col.actions'), s: 'SYS' },
        ]}
        after={<p className="sub2 fl-pad">{t('w01.selectHint')}</p>}
      >
        <ServerPagination
          queryKey={approver ? workflowKeys.inbox : workflowKeys.submissions}
          fetchPage={approver ? workflowPages.inbox : workflowPages.submissions}
          empty={<Empty text={t(approver ? 'w01.none' : 'w02.none')} />}
          onRows={keepShown}
        >
          {(items) => (
            <>
              {items.filter(visible).map((item) => {
                const on = selected.includes(item.approvalId);
                return (
                  <tr
                    key={item.approvalId}
                    aria-selected={on}
                    className={on ? 'fl-row-selected' : undefined}
                    onClick={() => toggle(item)}
                  >
                    <td>
                      <b>{item.approvalId}</b> {entityLabel(t, item.entityName)}{' '}
                      <span className="sub2">{item.entityKey}</span>
                      {item.reason && <div className="sub2">{item.reason}</div>}
                      {item.noApproverInScope && <span className="pill p-bad">{t('w01.noApprover')}</span>}
                      {item.levelCount > 2 && (
                        <span className="pill p-info">{t('w01.level', [item.level, item.levelCount])}</span>
                      )}
                    </td>
                    <td>
                      {item.moduleCode ? `${item.moduleCode} · ${t(`screen.${item.moduleCode}`)}` : '—'}
                    </td>
                    <td>
                      {item.submittedBy}
                      <div className="sub2">{dateTime(item.submittedAt)}</div>
                    </td>
                    <td>{amount(item.impactValue)}</td>
                    <td>
                      {t('w01.ageDays', [item.ageDays])}
                      <div className="sub2">
                        {item.targetTatHours === null
                          ? t('w01.noTarget')
                          : t('w01.target', [item.targetTatHours])}
                      </div>
                      {item.slaBreached && <span className="pill p-bad">{t('w01.breached')}</span>}
                    </td>
                    <td onClick={(e) => e.stopPropagation()}>
                      <RowActs
                        onView={() => setDetail(item)}
                        editWhy={t('w01.why.edit')}
                        onApprove={
                          item.canDecide ? () => setPending({ item, decision: 'APPROVE' }) : undefined
                        }
                      />
                    </td>
                  </tr>
                );
              })}
            </>
          )}
        </ServerPagination>
      </Tbl>
      {detail && (
        <ItemDetail
          item={detail}
          onClose={() => setDetail(null)}
          onDecide={(decision) => setPending({ item: detail, decision })}
        />
      )}
      {bulkOpen && (
        <ReasonDialog
          title={t('w01.bulkConfirm', [selected.length])}
          required={false}
          confirmLabel={t('w01.APPROVE')}
          onCancel={() => setBulkOpen(false)}
          onConfirm={(reason) => {
            setBulkOpen(false);
            void runBulkApprove(reason);
          }}
        />
      )}
      {returnOpen && (
        <ReasonDialog
          title={t('w01.returnConfirm', [selected.length])}
          confirmLabel={t('w01.RETURN')}
          onCancel={() => setReturnOpen(false)}
          onConfirm={(reason) => {
            setReturnOpen(false);
            void runReturnSelected(reason);
          }}
        />
      )}
      {pending && (
        <ReasonDialog
          title={t('w01.confirm', [t(`w01.${pending.decision}`), pending.item.approvalId])}
          required={pending.decision !== 'APPROVE'}
          confirmLabel={t(`w01.${pending.decision}`)}
          onCancel={() => setPending(null)}
          onConfirm={(reason) => {
            const { item, decision } = pending;
            setPending(null);
            void decide(item, decision, reason);
          }}
        />
      )}
    </div>
  );
}

/**
 * The item opened from View: everything the queue row does not show (office, the maker's reason,
 * the level, the submission time, the target) with the order documents (SRS-G-31, SRS-13-18) and
 * the decisions the session may take, each disabled with its reason otherwise (SRS-22-5).
 */
function ItemDetail({
  item,
  onClose,
  onDecide,
}: {
  item: Item;
  onClose: () => void;
  onDecide: (decision: Decision) => void;
}) {
  const { t } = useI18n();
  const docs = useItemDocuments(item.approvalId, true);
  return (
    <div className="fl-modal-backdrop" role="presentation">
      <div
        className="fl-modal fl-modal-wide"
        role="dialog"
        aria-modal="true"
        aria-label={t('w01.detail', [item.approvalId])}
      >
        <h2>{t('w01.detail', [item.approvalId])}</h2>
        <div className="kv">
          <div className="k">{t('w01.detail.entity')}</div>
          <div>
            {entityLabel(t, item.entityName)} <span className="sub2">{item.entityKey}</span>
          </div>
          <div className="k">{t('w01.col.module')}</div>
          <div>{item.moduleCode ? `${item.moduleCode} · ${t(`screen.${item.moduleCode}`)}` : '—'}</div>
          <div className="k">{t('w01.detail.location')}</div>
          <div>{item.locationLabel ?? '—'}</div>
          <div className="k">{t('w01.detail.submitted')}</div>
          <div>
            {item.submittedBy} · {dateTime(item.submittedAt)}
          </div>
          <div className="k">{t('w01.detail.reason')}</div>
          <div>{item.reason ?? '—'}</div>
          <div className="k">{t('w01.col.impact')}</div>
          <div>{amount(item.impactValue)}</div>
          <div className="k">{t('w01.detail.target')}</div>
          <div>
            {t('w01.ageDays', [item.ageDays])} ·{' '}
            {item.targetTatHours === null ? t('w01.noTarget') : t('w01.target', [item.targetTatHours])}
            {item.slaBreached && <span className="pill p-bad">{t('w01.breached')}</span>}
          </div>
          <div className="k">{t('w01.detail.level')}</div>
          <div>{t('w01.level', [item.level, item.levelCount])}</div>
          <div className="k">{t('w01.viewOrder')}</div>
          <div>
            {docs.data && docs.data.length === 0 && <span className="sub2">{t('w01.noDocuments')}</span>}
            {docs.data && docs.data.length > 0 && (
              <ul className="fl-list">
                {docs.data.map((d) => (
                  <li key={d.id}>
                    <a href={itemDocumentUrl(item.approvalId, d.id)} download>
                      {d.fileName}
                    </a>
                    {d.referenceNumber ? ` (${d.referenceNumber})` : ''}
                  </li>
                ))}
              </ul>
            )}
          </div>
        </div>
        <div className="toolbar">
          <button type="button" className="btn" onClick={onClose}>
            {t('common.close')}
          </button>
          <span className="sp" />
          {(['APPROVE', 'RETURN', 'REJECT'] as const).map((decision) => (
            <button
              key={decision}
              type="button"
              className={`btn ${decision === 'APPROVE' ? 'pri' : ''}${item.canDecide ? '' : ' lock'}`}
              disabled={!item.canDecide}
              title={item.canDecide ? undefined : t(item.denialKey ?? 'reason.checkerOnly')}
              data-why={item.canDecide ? undefined : t(item.denialKey ?? 'reason.checkerOnly')}
              onClick={() => onDecide(decision)}
            >
              {t(`w01.${decision}`)}
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}
