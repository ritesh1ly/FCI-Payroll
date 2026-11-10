'use client';

import { useI18n } from '@/shared/i18n';
import { useOwnRecord } from '../orchestration/employeeQueries';
import { amount, Card, Problem, StatusPill, Tile } from '@/shared/ui';

/**
 * E-01 eSeva: the signed-in employee's own record only (the API never returns another one). PAN, bank
 * account and Aadhaar masked (SRS-17-10, l.3305). The drawn E-01 prints the claims note itself:
 * {@code claims} false leaves the card out there.
 */
export function EsevaRecord({ claims = true }: { claims?: boolean } = {}) {
  const { t } = useI18n();
  const record = useOwnRecord();
  const d = record.data;
  return (
    <div className="wireframe-body-container">
      <Problem error={record.error} />
      {d && (
        <>
          <div className="tiles">
            <Tile icon="🪪" label={t('m01.eis')} value={d.eis} sub={d.location ?? ''} />
            <Tile icon="💼" label={t('m01.designation')} value={d.designation ?? '—'} sub={d.band ?? ''} />
            <Tile
              icon="₹"
              label={t('m01.basic')}
              value={amount(d.currentBasic)}
              sub={d.payPattern ?? ''}
              tone="ok"
            />
            <Tile icon="📋" label={t('m01.status')} value={<StatusPill status={d.status} />} />
          </div>
          <Card title={t('e01.title', [`${d.firstName} ${d.lastName ?? ''}`.trim()])}>
            <div className="bd">
              <div className="kv">
                <span className="k">{t('m01.pan')}</span>
                <span>{d.panMasked ?? '—'}</span>
                <span className="k">{t('m01.aadhaar')}</span>
                <span>{d.aadhaarMasked ?? '—'}</span>
                <span className="k">{t('m01.cpf')}</span>
                <span>{d.cpfCode ?? '—'}</span>
                <span className="k">{t('field.mobileNumber')}</span>
                <span>{d.mobileNumber ?? '—'}</span>
                <span className="k">{t('field.emailAddress')}</span>
                <span>{d.emailAddress ?? '—'}</span>
                <span className="k">{t('field.bankAccount')}</span>
                <span>{d.bankAccountMasked ?? '—'}</span>
                <span className="k">{t('m01.providentFund')}</span>
                <span>{d.providentFundType ?? '—'}</span>
              </div>
              <p className="fl-hint">{t('e01.note')}</p>
            </div>
          </Card>
          {/* SRS-17-2 (alt flow): no employee claim is submitted in eSeva; the member is told where. */}
          {claims && (
            <Card title={t('e01.claims')}>
              <p className="bd fl-hint" role="note">
                {t('e01.claimsInHrms')}
              </p>
            </Card>
          )}
        </>
      )}
    </div>
  );
}
