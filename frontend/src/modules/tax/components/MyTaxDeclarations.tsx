'use client';

import { useState } from 'react';
import { useDocumentTypes } from '@/modules/general';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { businessToday } from '@/shared/time';
import { Card, date, Empty, Problem, Src, useToast } from '@/shared/ui';
import type {
  DeclarationKind,
  DeclarationRound,
  NewDeclarationLine,
  ProofFile,
  TaxRegime,
} from '../api/taxApi';
import { allowedOf, declarationProblems, isAmount, sectionsFor } from '../business/declarationRules';
import { useMyDeclarations, useSubmitDeclarations, proofUrl } from '../orchestration/taxQueries';
import { TaxWhatIf } from './TaxWhatIf';

const KINDS: readonly DeclarationKind[] = ['INVESTMENT', 'HRA_RENT'];

function rupees(n: number): string {
  return `₹${n.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

function blankLine(): NewDeclarationLine {
  return { kind: 'INVESTMENT', sectionCode: '', declared: '', proof: null };
}

/** Why the member cannot declare now, or nothing. */
function blockedBecause(windowOpen: boolean, atChecker: boolean): string | null {
  if (!windowOpen) return 'e01t.windowClosed';
  if (atChecker) return 'error.TAX-409-DECLARATION-PENDING';
  return null;
}

/**
 * E-01 card: the member's income-tax and rent declarations with the regime choice, in Payroll
 * eSeva (SRS-7-1, SRS-7-15, SRS-7-16, SRS-7-24, SRS-17-2; BR-TAX-001, BR-TAX-002, BR-TAX-014). The
 * member submits while the window is open; a line above its section limit is capped and the card
 * says so; the round is verified by the Payroll Maker and approved by the Payroll Checker, after
 * which the regime and the rent declaration are frozen for the year.
 */
export function MyTaxDeclarations() {
  const { t } = useI18n();
  const toast = useToast();
  const me = useMe();
  const year = useMyDeclarations();
  const submit = useSubmitDeclarations();
  const mayCreate = can(me.data, 'E-01', 'CREATE');
  const types = useDocumentTypes(mayCreate);
  const [regime, setRegime] = useState<TaxRegime | null>(null);
  const [lines, setLines] = useState<NewDeclarationLine[] | null>(null);
  const [error, setError] = useState<unknown>(null);
  const data = year.data;
  const rounds = data?.rounds ?? [];
  const atChecker = rounds[0]?.status === 'VERIFIED';
  const rentFrozen = rounds.some(
    (r) => r.status === 'APPROVED' && r.lines.some((l) => l.kind === 'HRA_RENT' && l.frozen),
  );
  const chosen = data?.regimeFrozen ? data.regime : (regime ?? data?.regime ?? null);
  const today = businessToday();
  const offered = sectionsFor(data?.sections ?? [], chosen);
  const problems = lines ? declarationProblems({ regime: chosen, lines }, data?.sections ?? [], today) : [];
  const blocked = blockedBecause(Boolean(data?.windowOpen), atChecker);

  function update(i: number, change: Partial<NewDeclarationLine>) {
    setLines((ls) => (ls ?? []).map((l, j) => (j === i ? { ...l, ...change } : l)));
  }

  function updateProof(i: number, change: Partial<ProofFile>) {
    setLines((ls) =>
      (ls ?? []).map((l, j) => (j === i && l.proof ? { ...l, proof: { ...l.proof, ...change } } : l)),
    );
  }

  async function send(draft: NewDeclarationLine[]) {
    setError(null);
    try {
      const round = await submit.mutateAsync({
        regime: data?.regimeFrozen ? null : chosen,
        lines: draft.map((l) => ({ ...l, declared: l.declared.trim() })),
      });
      toast(t('e01t.submitted', [round.number]));
      setLines(null);
    } catch (cause) {
      setError(cause);
    }
  }

  if (year.isPending) return <p className="fl-hint">{t('common.loading')}</p>;
  if (!data) return <Problem error={year.error} />;

  return (
    <>
      <Card title={t('e01t.title', [data.finYear])}>
        <p className="sub2">{t('e01t.hint')}</p>
        <div className="toolbar">
          <span className={`pill ${data.windowOpen ? 'p-ok' : 'p-warn'}`}>
            {t(data.windowOpen ? 'e01t.windowOpen' : 'e01t.windowClosed')}
          </span>
          {data.proofsUntil && (
            <span className="sub2">{t('e01t.proofsUntil', [date(data.proofsUntil)])}</span>
          )}
          {mayCreate && lines === null && (
            <button
              type="button"
              className="btn"
              disabled={blocked !== null}
              title={blocked ? t(blocked) : undefined}
              onClick={() => {
                setError(null);
                setLines([]);
              }}
            >
              {t('action.declare')}
            </button>
          )}
        </div>
        <fieldset className="rf-field" disabled={lines === null || data.regimeFrozen}>
          <legend>
            {t('e01t.regime')}
            <Src s="ESS" />
            {data.regimeFrozen && <span className="pill">{t('e01t.frozen')}</span>}
          </legend>
          {(['OLD', 'NEW'] as const).map((r) => (
            <label key={r}>
              <input
                type="radio"
                name="e01t-regime"
                value={r}
                checked={chosen === r}
                onChange={() => setRegime(r)}
              />{' '}
              {t(`e01t.regime.${r}`)}
            </label>
          ))}
        </fieldset>
        {lines !== null && (
          <div className="bd">
            <Problem error={error} />
            {rentFrozen && <p className="sub2">{t('e01t.rentFrozen')}</p>}
            <table className="t" aria-label={t('e01t.lines')}>
              <thead>
                <tr>
                  <th>
                    {t('e01t.kind')}
                    <Src s="ESS" />
                  </th>
                  <th>
                    {t('m18s.section')}
                    <Src s="ESS" />
                  </th>
                  <th>
                    {t('e01t.declared')}
                    <Src s="ESS" />
                  </th>
                  <th>
                    {t('e01t.allowed')}
                    <Src s="CALC" />
                  </th>
                  <th>
                    {t('e01t.proof')}
                    <Src s="ESS" />
                  </th>
                  <th aria-label={t('common.actions')} />
                </tr>
              </thead>
              <tbody>
                {lines.map((l, i) => {
                  const section = offered.find((s) => s.code === l.sectionCode);
                  const amount = isAmount(l.declared) ? Number(l.declared) : null;
                  const allowed = section && amount !== null ? allowedOf(amount, section.limit) : null;
                  return (
                    <tr key={i}>
                      <td>
                        <select
                          aria-label={t('e01t.kindOf', [i + 1])}
                          value={l.kind}
                          onChange={(e) => update(i, { kind: e.target.value as DeclarationKind })}
                        >
                          {KINDS.filter((k) => k === l.kind || !(k === 'HRA_RENT' && rentFrozen)).map((k) => (
                            <option key={k} value={k}>
                              {t(`e01t.kind.${k}`)}
                            </option>
                          ))}
                        </select>
                      </td>
                      <td>
                        <select
                          aria-label={t('e01t.sectionOf', [i + 1])}
                          value={l.sectionCode}
                          onChange={(e) => update(i, { sectionCode: e.target.value })}
                        >
                          <option value="">—</option>
                          {offered.map((s) => (
                            <option key={s.code} value={s.code}>
                              {s.code} · {s.description}
                            </option>
                          ))}
                        </select>
                        {section && (
                          <div className="sub2">
                            {section.limit === null
                              ? t('m18s.noLimit')
                              : t('e01t.limitOf', [rupees(section.limit)])}
                          </div>
                        )}
                      </td>
                      <td>
                        <input
                          aria-label={t('e01t.declaredOf', [i + 1])}
                          inputMode="decimal"
                          maxLength={15}
                          value={l.declared}
                          onChange={(e) => update(i, { declared: e.target.value })}
                        />
                      </td>
                      <td>
                        {allowed === null ? (
                          '—'
                        ) : (
                          <>
                            {rupees(allowed)}
                            {amount !== null && allowed < amount && (
                              <div className="pill p-warn">{t('e01t.cappedAt', [rupees(allowed)])}</div>
                            )}
                          </>
                        )}
                      </td>
                      <td>
                        {l.proof === null ? (
                          <input
                            type="file"
                            aria-label={t('e01t.proofOf', [i + 1])}
                            accept="application/pdf,image/jpeg,image/png"
                            onChange={(e) => {
                              const file = e.target.files?.[0];
                              if (file)
                                update(i, {
                                  proof: {
                                    documentType: 'CERTIFICATE',
                                    reference: '',
                                    documentDate: '',
                                    issuingAuthority: '',
                                    file,
                                  },
                                });
                            }}
                          />
                        ) : (
                          <div className="rf-grid">
                            <span className="sub2">{l.proof.file.name}</span>
                            <select
                              aria-label={t('e01t.proofTypeOf', [i + 1])}
                              value={l.proof.documentType}
                              onChange={(e) => updateProof(i, { documentType: e.target.value })}
                            >
                              {(types.data ?? []).map((x) => (
                                <option key={x.code} value={x.code}>
                                  {x.name}
                                </option>
                              ))}
                            </select>
                            <input
                              type="date"
                              aria-label={t('e01t.proofDateOf', [i + 1])}
                              max={today}
                              value={l.proof.documentDate}
                              onChange={(e) => updateProof(i, { documentDate: e.target.value })}
                            />
                            <input
                              aria-label={t('e01t.proofIssuerOf', [i + 1])}
                              maxLength={200}
                              value={l.proof.issuingAuthority}
                              onChange={(e) => updateProof(i, { issuingAuthority: e.target.value })}
                            />
                            <input
                              aria-label={t('e01t.proofReferenceOf', [i + 1])}
                              maxLength={60}
                              value={l.proof.reference}
                              onChange={(e) => updateProof(i, { reference: e.target.value })}
                            />
                          </div>
                        )}
                      </td>
                      <td>
                        <button
                          type="button"
                          className="ia"
                          aria-label={t('m19p.removeOf', [i + 1])}
                          onClick={() => setLines(lines.filter((_, j) => j !== i))}
                        >
                          {t('common.remove')}
                        </button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
            <button type="button" className="btn sm" onClick={() => setLines([...lines, blankLine()])}>
              {t('e01t.addLine')}
            </button>
            {problems.length > 0 && (
              <ul className="bd fl-list" aria-label={t('m18s.problems')}>
                {problems.map((p) => (
                  <li key={p}>{t(p)}</li>
                ))}
              </ul>
            )}
            <div className="rf-actbar">
              <button type="button" className="btn" onClick={() => setLines(null)}>
                {t('common.cancel')}
              </button>
              <button
                type="button"
                className="btn pri"
                disabled={problems.length > 0 || submit.isPending}
                onClick={() => void send(lines)}
              >
                {t('common.submit')}
              </button>
            </div>
          </div>
        )}
        {lines === null && <Problem error={error} />}
        {rounds.length === 0 ? <Empty text={t('e01t.none')} /> : <Rounds rounds={rounds} />}
      </Card>
      {/* SRS-7-28: the employee's own what-if forecast of the year. */}
      <TaxWhatIf eis={null} finYear={data.finYear} />
    </>
  );
}

/** The member's rounds of the year, newest first, with what was capped and why one came back. */
function Rounds({ rounds }: { rounds: DeclarationRound[] }) {
  const { t } = useI18n();
  return (
    <ul className="fl-list" aria-label={t('e01t.rounds')}>
      {rounds.map((r) => (
        <li key={r.id} data-reference={`round-${r.number}`}>
          <b>{t('e01t.round', [r.number])}</b> <span className="pill">{t(`e01t.status.${r.status}`)}</span>{' '}
          {r.regime && t(`e01t.regime.${r.regime}`)}
          {r.returnReason && <div className="pill p-warn">{t('e01t.returnedBecause', [r.returnReason])}</div>}
          {r.proofDeadlineReversed && <div className="pill p-warn">{t('e01t.reversed')}</div>}
          <ul>
            {r.lines.map((l) => (
              <li key={l.id}>
                {t(`e01t.kind.${l.kind}`)} {l.sectionCode}: {rupees(l.declared)}
                {l.capped && ` — ${t('e01t.cappedAt', [rupees(l.allowed)])}`}
                {l.frozen && ` · ${t('e01t.frozen')}`}
                {l.proofId !== null && (
                  <>
                    {' · '}
                    <a href={proofUrl(r.id, l.proofId)}>{t('e01t.proof')}</a>
                  </>
                )}
              </li>
            ))}
          </ul>
        </li>
      ))}
    </ul>
  );
}
