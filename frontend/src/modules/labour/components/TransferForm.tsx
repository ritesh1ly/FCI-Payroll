'use client';

import { useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { businessToday } from '@/shared/time';
import { Problem, Src, TextAreaField, useToast } from '@/shared/ui';
import { transferBlocked } from '../business/labourRules';
import { useMoveMember } from '../orchestration/labourQueries';
import { GangPicker } from './GangPicker';

/** The labour a transfer is entered for. */
export interface TransferMember {
  employeeId: number;
  labourCode: string;
  payrollType: string;
  /** The member's own gang, never offered as the receiving one. */
  gangId?: number;
}

/**
 * A labour transfer (M-02 l.4429 "Transfer ... starts the action on M-02 with the order attached";
 * SRS-2-6 between gangs, depots and Divisional Offices; NWNP l.4937): the receiving gang of the
 * payroll type in any office (OD-88), the transfer date (a future date is allowed), the order
 * (reference, date, issuing authority) and the reason; the Payroll Checker approves. Used on the M-02
 * row and on the M-09 member list.
 */
export function TransferForm({ member, onDone }: { member: TransferMember; onDone: () => void }) {
  const { t } = useI18n();
  const toast = useToast();
  const today = businessToday();
  const move = useMoveMember();
  const [error, setError] = useState<unknown>(null);
  const [entry, setEntry] = useState({
    toGangId: null as number | null,
    transferDate: today,
    orderReference: '',
    orderDate: today,
    issuingAuthority: '',
    reason: '',
  });
  const why = transferBlocked(entry, today);

  async function send() {
    if (entry.toGangId === null) return;
    setError(null);
    try {
      await move.mutateAsync({
        kind: 'TRANSFER',
        employeeId: member.employeeId,
        toGangId: entry.toGangId,
        from: entry.transferDate,
        to: null,
        reason: entry.reason,
        order: {
          orderReference: entry.orderReference,
          orderDate: entry.orderDate,
          issuingAuthority: entry.issuingAuthority,
        },
      });
      toast(t('m09.moveSent'));
      onDone();
    } catch (cause) {
      setError(cause);
    }
  }

  return (
    <div className="form-grid" aria-label={t('m09.move.TRANSFER', [member.labourCode])}>
      <h4>{t('m09.move.TRANSFER', [member.labourCode])}</h4>
      <GangPicker
        label={t('m09.toGang')}
        source="ENT"
        value={entry.toGangId}
        exclude={member.gangId}
        transferTarget
        search={{ status: 'ACTIVE', gangType: 'HANDLING', payrollType: member.payrollType }}
        onChange={(g) => setEntry({ ...entry, toGangId: g ? g.gangId : null })}
      />
      <label>
        {t('m09.transferDate')}
        <Src s="ENT" />
        <input
          type="date"
          value={entry.transferDate}
          onChange={(e) => setEntry({ ...entry, transferDate: e.target.value })}
        />
      </label>
      <label>
        {t('m02.orderReference')}
        <Src s="ENT" />
        <input
          maxLength={100}
          value={entry.orderReference}
          onChange={(e) => setEntry({ ...entry, orderReference: e.target.value })}
        />
      </label>
      <label>
        {t('m02.orderDate')}
        <Src s="ENT" />
        <input
          type="date"
          max={today}
          value={entry.orderDate}
          onChange={(e) => setEntry({ ...entry, orderDate: e.target.value })}
        />
      </label>
      <label>
        {t('m02.issuingAuthority')}
        <Src s="ENT" />
        <input
          maxLength={150}
          value={entry.issuingAuthority}
          onChange={(e) => setEntry({ ...entry, issuingAuthority: e.target.value })}
        />
      </label>
      <label>
        {t('m09.moveReason')}
        <Src s="ENT" />
        <TextAreaField
          maxLength={500}
          value={entry.reason}
          onChange={(e) => setEntry({ ...entry, reason: e.target.value })}
        />
      </label>
      <div className="toolbar">
        <button
          type="button"
          className="btn primary"
          disabled={why !== null || move.isPending}
          title={why ? t(why) : undefined}
          onClick={() => void send()}
        >
          {t('action.m09.sendMove')}
        </button>
        <button type="button" className="btn" onClick={onDone}>
          {t('common.cancel')}
        </button>
      </div>
      {error !== null && <Problem error={error} />}
    </div>
  );
}
