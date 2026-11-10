'use client';

import { useCallback, useMemo, useRef, useState } from 'react';
import { ApiError } from '@/shared/api';
import { useI18n } from '@/shared/i18n';
import type { Me, MenuItem } from '@/shared/session';
import { type ActionView, usePerformAction, useScreenActions } from '@/modules/workflow';
import { ReasonDialog, useToast } from '@/shared/ui';
import { useWireframeBody } from '../orchestration/wireframeQueries';
import { SCREENS } from './screenRegistry';
import { filterRowsByStatus, type WfNode, Wireframe } from './Wireframe';

/** Actions decided elsewhere (approvals in the inbox) or that need a record (M-01 edit). */
const NOT_IN_TOOLBAR = new Set(['APPROVE', 'VIEW']);

/** The labour engine a route opens where the screen is drawn under several groups (&engine=DPS). */
function engineOf(route: string | null | undefined): string | null {
  const view = /[?&]engine=(DPS|NWNP)\b/.exec(route ?? '');
  return view ? (view[1] ?? null) : null;
}

/** The view a route opens on a screen drawn under two groups (I-04: &view=LOANS). */
function viewOf(route: string | null | undefined): string | null {
  const view = /[?&]view=(LOANS)\b/.exec(route ?? '');
  return view ? (view[1] ?? null) : null;
}

/**
 * One screen in the wireframe v6.1 layout: page header (title, code · crumb, the governed buttons
 * of screen_action_inventory, disabled with the reason when the session lacks the permission), the
 * body, previous / next navigation and the footer. The design pack's reviewer aids (annotations,
 * FRS tags, field-source legend, RBAC demonstration strip) are not product content and are not
 * rendered (scripts/build-wireframes.mjs removes them from the bodies).
 */
export function ScreenView({
  code,
  me,
  order,
  onNavigate,
  entries,
  route,
  onOpen,
}: {
  code: string;
  me: Me;
  order: string[];
  onNavigate: (code: string) => void;
  /** The sidebar's entries, so previous / next follow the open view of a screen drawn twice. */
  entries?: MenuItem[];
  /** The route open now. */
  route?: string;
  onOpen?: (item: MenuItem) => void;
}) {
  const { t } = useI18n();
  const toast = useToast();
  const performAction = usePerformAction();
  const definition = SCREENS[code];
  // The body comes from the server after its VIEW check (never from the bundle), and only for a
  // screen without its own component: M-01 draws its own buttons.
  const wireframe = useWireframeBody(
    code,
    definition?.wireframe === true && !definition.component,
    `${me.role ?? ''}@${me.location?.code ?? ''}`,
  );
  const actions = useScreenActions();
  const [pending, setPending] = useState<ActionView | null>(null);
  const bodyRef = useRef<HTMLDivElement>(null);

  const toolbar = useMemo(
    () =>
      (actions.data ?? []).filter(
        (a) =>
          a.screenCode === code &&
          !NOT_IN_TOOLBAR.has(a.actionCode) &&
          !(code === 'M-01' && a.actionCode === 'EDIT'),
      ),
    [actions.data, code],
  );

  const perform = useCallback(
    async (action: ActionView, reason: string | null) => {
      try {
        const result = await performAction.mutateAsync({
          screen: action.screenCode,
          action: action.actionCode,
          reason,
        });
        toast(
          result.outcome === 'SUBMITTED'
            ? t('action.submitted', [result.approvalId])
            : t('action.recorded', [t(action.labelKey)]),
        );
      } catch (cause) {
        if (cause instanceof ApiError && cause.messageKey !== 'otp.cancelled')
          toast(t(cause.messageKey, cause.args));
      }
    },
    [performAction, toast, t],
  );

  const onAction = useCallback(
    (act: string, args: string[]) => {
      switch (act) {
        case 'setM1Filter':
          filterRowsByStatus(bodyRef.current, args[0] ?? '');
          break;
        case 'scrollToForm': {
          const target = args[0] ? document.getElementById(args[0]) : null;
          target?.scrollIntoView({ behavior: 'smooth', block: 'center' });
          target?.classList.add('flashform');
          setTimeout(() => target?.classList.remove('flashform'), 1800);
          break;
        }
        case 'toast':
          toast(args[0] ?? '');
          break;
        default:
          // doAct / openForm / saveDARate inside a design-pack mock-up: logged in the session only,
          // exactly as on the live site. Governed actions are the buttons above the body.
          toast(t('wireframe.logged', [args[0] ?? act]));
      }
    },
    [toast, t],
  );
  const handlers = useMemo(() => ({ onAction }), [onAction]);

  if (!definition) return null;
  const Component = definition.component;
  const body = (wireframe.data ?? []) as WfNode[];
  const index = order.indexOf(code);
  const previous = index > 0 ? order[index - 1] : null;
  const next = index >= 0 && index < order.length - 1 ? order[index + 1] : null;
  const at = entries && route ? entries.findIndex((e) => e.route === route) : -1;
  const previousEntry = at > 0 ? entries?.[at - 1] : undefined;
  const nextEntry = at >= 0 ? entries?.[at + 1] : undefined;
  /** "Work-Slip Capture ▸ DPS Labour": a screen's name in the view of a labour engine. */
  const named = (screen: string, itemRoute: string | null | undefined) => {
    const engine = engineOf(itemRoute);
    const view = viewOf(itemRoute);
    if (view) return `${t(`screen.${screen}`)} ▸ ${t(`view.suffix.${view}`)}`;
    return engine ? `${t(`screen.${screen}`)} ▸ ${t(`engine.suffix.${engine}`)}` : t(`screen.${screen}`);
  };

  return (
    <div className="screen">
      <div className="ph">
        <div>
          <h1>
            {viewOf(route) ? (
              <>
                {t(`screen.${code}`)} <span className="sub2">▸ {t(`view.suffix.${viewOf(route)}`)}</span>
              </>
            ) : (
              named(code, route)
            )}
          </h1>
          <div className="crumb">
            {code} · {t(`crumb.${code}`)}
          </div>
        </div>
        <div className="spacer" />
        {definition.wireframe === true && toolbar.length > 0 && (
          <div className="toolbar">
            {toolbar.map((action) => (
              <button
                key={action.actionCode}
                type="button"
                className={`btn ${action.actionCode === 'EXPORT' ? '' : 'pri'}${action.allowed ? '' : ' lock'}`}
                disabled={!action.allowed}
                title={action.allowed ? undefined : t(action.disabledReasonKey ?? 'reason.noPermission')}
                onClick={() =>
                  action.actionCode === 'EXPORT' ? void perform(action, null) : setPending(action)
                }
              >
                {t(action.labelKey)}
              </button>
            ))}
          </div>
        )}
      </div>
      <div ref={bodyRef}>{Component ? <Component /> : <Wireframe nodes={body} handlers={handlers} />}</div>
      {order.length > 1 && (
        <div className="pnav">
          {at >= 0
            ? previousEntry && (
                <button type="button" className="btn" onClick={() => onOpen?.(previousEntry)}>
                  ← {named(previousEntry.code, previousEntry.route)}
                </button>
              )
            : previous && (
                <button type="button" className="btn" onClick={() => onNavigate(previous)}>
                  ← {t(`screen.${previous}`)}
                </button>
              )}
          <div className="spacer" />
          {at >= 0
            ? nextEntry && (
                <button type="button" className="btn pri" onClick={() => onOpen?.(nextEntry)}>
                  {named(nextEntry.code, nextEntry.route)} →
                </button>
              )
            : next && (
                <button type="button" className="btn pri" onClick={() => onNavigate(next)}>
                  {t(`screen.${next}`)} →
                </button>
              )}
        </div>
      )}
      <div className="foot">
        <span>{t('screen.footer', [code])}</span>
        <span>{t('screen.governance')}</span>
      </div>
      {pending && (
        <ReasonDialog
          title={t(pending.labelKey)}
          onCancel={() => setPending(null)}
          onConfirm={(reason) => {
            const action = pending;
            setPending(null);
            void perform(action, reason);
          }}
        />
      )}
    </div>
  );
}
