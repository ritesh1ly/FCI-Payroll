'use client';

import { type FormEvent, useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { useDialogFocus, TextAreaField } from '@/shared/ui';
import { memberList, type ReturnInput, type ReturnScope } from '../business/cycleRules';

/** R-ASCII: plain text only, as the server checks (`@PrintableAscii`). */
const PLAIN_TEXT = /^[\x20-\x7E\t\r\n]*$/;
const SCOPES: ReturnScope[] = ['INSTANCE', 'ORG_UNIT', 'MEMBERS'];

/**
 * SRS-15-30: the Checker's return of a computed or verified cycle: a scope (the whole instance, an
 * office beneath it, or named members by EIS number) and a reason. The server checks the office and
 * the members against the run; this dialog only asks for what the scope needs.
 */
export function ReturnDialog({
  title,
  onConfirm,
  onCancel,
}: {
  title: string;
  onConfirm: (input: ReturnInput) => void;
  onCancel: () => void;
}) {
  const { t } = useI18n();
  const dialog = useDialogFocus<HTMLFormElement>();
  const [scope, setScope] = useState<ReturnScope>('INSTANCE');
  const [office, setOffice] = useState('');
  const [members, setMembers] = useState('');
  const [reason, setReason] = useState('');
  const named = memberList(members);
  const plain = PLAIN_TEXT.test(reason) && PLAIN_TEXT.test(office) && PLAIN_TEXT.test(members);
  const ready =
    plain &&
    reason.trim() !== '' &&
    (scope !== 'ORG_UNIT' || office.trim() !== '') &&
    (scope !== 'MEMBERS' || named.length > 0);

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!ready) return;
    onConfirm({
      scope,
      reason: reason.trim(),
      ...(scope === 'ORG_UNIT' ? { orgUnitCode: office.trim() } : {}),
      ...(scope === 'MEMBERS' ? { members: named } : {}),
    });
  }

  return (
    <div className="fl-modal-backdrop" role="presentation">
      <form
        ref={dialog}
        className="fl-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="return-title"
        onSubmit={submit}
      >
        <h2 id="return-title">{title}</h2>
        <p className="sub2">{t('cyc.returnNote')}</p>
        <fieldset className="fl-field">
          <legend>{t('cyc.returnScope')}</legend>
          {SCOPES.map((s) => (
            <label key={s}>
              <input
                type="radio"
                name="scope"
                value={s}
                checked={scope === s}
                onChange={() => setScope(s)}
                data-autofocus={s === 'INSTANCE' ? true : undefined}
              />{' '}
              {t(`cyc.returnScope.${s}`)}
            </label>
          ))}
        </fieldset>
        {scope === 'ORG_UNIT' && (
          <label className="fl-field">
            {t('cyc.returnOffice')}
            <input name="office" maxLength={20} value={office} onChange={(e) => setOffice(e.target.value)} />
          </label>
        )}
        {scope === 'MEMBERS' && (
          <label className="fl-field">
            {t('cyc.returnMembers')}
            <textarea name="members" rows={3} value={members} onChange={(e) => setMembers(e.target.value)} />
          </label>
        )}
        {scope === 'MEMBERS' && <p className="sub2">{t('cyc.returnMembersCount', [named.length])}</p>}
        <label className="fl-field">
          {t('common.reason')}
          <TextAreaField
            name="reason"
            maxLength={500}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
          />
        </label>
        {!plain && (
          <p className="fl-error" role="alert">
            {t('common.plainTextOnly')}
          </p>
        )}
        <div className="fl-actions">
          <button type="button" className="btn" onClick={onCancel}>
            {t('common.cancel')}
          </button>
          <button type="submit" className="btn pri" disabled={!ready}>
            {t('cyc.action.return')}
          </button>
        </div>
      </form>
    </div>
  );
}
