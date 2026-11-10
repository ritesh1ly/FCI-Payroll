'use client';

import { useState } from 'react';
import {
  EMPTY_ORDER,
  OrderFields,
  orderComplete,
  type OrderDraft,
  todayIso,
  useDocumentTypes,
} from '@/modules/general';
import { useI18n } from '@/shared/i18n';
import { dateTime, Empty, Problem, useToast } from '@/shared/ui';
import type { UpdateRequest } from '../api/hrmsUpdateApi';
import { orderCopyUrl, useAttachOrder, useOrderCopies } from '../orchestration/hrmsUpdateQueries';

/**
 * SRS-1-70 "may attach the order" (plan 16 D16-32): the order copies of one HRMS Update Request,
 * each downloadable, and the form to attach another (the member on an own request, the I-21 desk on
 * a request of its unit).
 */
export function UpdateOrderCopies({
  request,
  mine,
  mayAttach,
  onClose,
  initialFile = null,
}: {
  request: UpdateRequest;
  mine: boolean;
  mayAttach: boolean;
  onClose: () => void;
  /** The file chosen on the drawn E-01 form: the order details are still asked here (SRS-G-30). */
  initialFile?: File | null;
}) {
  const { t } = useI18n();
  const toast = useToast();
  const copies = useOrderCopies(request.id, mine);
  const attach = useAttachOrder(request.id, mine);
  const types = useDocumentTypes(mayAttach);
  const [order, setOrder] = useState<OrderDraft>({ ...EMPTY_ORDER, file: initialFile });
  const [error, setError] = useState<unknown>(null);
  const today = todayIso();
  const complete = orderComplete(order, types.data ?? [], today) && order.file !== null;
  async function send() {
    if (!order.file) return;
    setError(null);
    try {
      await attach.mutateAsync({ ...order, file: order.file });
      setOrder(EMPTY_ORDER);
      toast(t('i21.upd.order.attached'));
    } catch (cause) {
      setError(cause);
    }
  }
  return (
    <section className="card" aria-label={t('i21.upd.order.title', [request.requestNo])}>
      <div className="hd">
        <h3>{t('i21.upd.order.title', [request.requestNo])}</h3>
        <button type="button" className="btn" onClick={onClose}>
          {t('common.close')}
        </button>
      </div>
      <Problem error={copies.error ?? error} />
      {copies.data && copies.data.length === 0 && <Empty text={t('i21.upd.order.none')} />}
      {copies.data && copies.data.length > 0 && (
        <ul className="fl-list" aria-label={t('i21.upd.order.list')}>
          {copies.data.map((c) => (
            <li key={c.id}>
              <a href={orderCopyUrl(request.id, mine, c.id)}>{c.fileName}</a> · {c.documentTypeName}
              {c.referenceNumber ? ` · ${c.referenceNumber}` : ''} · {c.uploadedBy} · {dateTime(c.uploadedAt)}
            </li>
          ))}
        </ul>
      )}
      {mayAttach && (
        <>
          <OrderFields
            idPrefix="upd"
            value={order}
            types={types.data ?? []}
            today={today}
            onChange={setOrder}
          />
          <button
            type="button"
            className="btn pri"
            disabled={!complete || attach.isPending}
            title={complete ? undefined : t('i21.upd.order.incomplete')}
            onClick={() => void send()}
          >
            {t('i21.upd.order.attach')}
          </button>
        </>
      )}
    </section>
  );
}
