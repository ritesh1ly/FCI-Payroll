'use client';

import { useEffect, useState } from 'react';
import { HistoryButton } from '@/modules/audit';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { businessToday } from '@/shared/time';
import { Card, Empty, Problem, ServerPagination, Src, date, useToast, TextAreaField } from '@/shared/ui';
import type { GangMember, GangRole, GangRow, NewGang } from '../api/labourApi';
import { GANG_ROLES, PAYROLL_TYPES, gangBlocked, reasonOk, strengthBlocked } from '../business/labourRules';
import {
  findLabour,
  gangsPage,
  labourKeys,
  useCreateGang,
  useEndGang,
  useGangChange,
  useGangMembers,
  useMoveMember,
  useOptions,
  useStrength,
} from '../orchestration/labourQueries';
import { GangPicker } from './GangPicker';
import { GangStrengthReport } from './GangStrengthReport';
import { TransferForm } from './TransferForm';

/** A member's transfer or attachment being entered (M-09). */
interface MoveDraft {
  kind: 'TRANSFER' | 'ATTACH';
  member: GangMember;
  toGangId: number | null;
  from: string;
  to: string;
  reason: string;
}

/** A gang's join or Sardar / Mandal change being entered (M-09). */
interface ChangeDraft {
  role: 'JOIN' | 'SARDAR' | 'MANDAL';
  code: string;
  employeeId: number | null;
  from: string;
  reason: string;
}

/**
 * M-09 Gang / Shed Structure (plan 20 phase 20a; SRS-2-1, SRS-2-46, SRS-2-48; BR-LABM-001): the gangs
 * of the session's depots with their Sanctioned Strength and members. Establishment / HR forms a
 * handling gang (exactly one Sardar and one Mandal, the name unique in the depot) and changes its
 * Sanctioned Strength from a date; the Payroll Checker approves each in the Approvals Inbox. The
 * depot's Ancillary Pool is created by the system with its first gang and is never merged. A handling
 * member is transferred (M-02, SRS-2-6) or attached for dates (SRS-2-47) to another gang, and an
 * empty gang is ended at a date (SRS-2-7), each for the Payroll Checker.
 */
export function GangStructure({
  opening = null,
  startNew = false,
}: {
  /** The gang a drawn button opened: its members, strength and end date show at once. */
  opening?: GangRow | null;
  /** The drawn New Gang: the gang's entry opens at once. */
  startNew?: boolean;
} = {}) {
  const { t } = useI18n();
  const me = useMe();
  const toast = useToast();
  const options = useOptions();
  const today = businessToday();
  const [depot, setDepot] = useState<number | null>(null);
  const [selected, setSelected] = useState<GangRow | null>(opening);
  // M-09 l.4496: the members (attached labour for the days attached) as of a chosen date.
  const [asOf, setAsOf] = useState(today);
  const members = useGangMembers(selected?.gangId ?? null, asOf);
  const createGang = useCreateGang();
  const strength = useStrength();
  const [error, setError] = useState<unknown>(null);
  const [gang, setGang] = useState<NewGang | null>(null);
  const [labourCode, setLabourCode] = useState('');
  const [role, setRole] = useState<GangRole>('HANDLING');
  const [names, setNames] = useState<Record<number, string>>({});
  const [missing, setMissing] = useState<string | null>(null);
  const [newStrength, setNewStrength] = useState({ strength: 1, effectiveFrom: today, reason: '' });
  const moveMember = useMoveMember();
  const endGang = useEndGang();
  const [move, setMove] = useState<MoveDraft | null>(null);
  const [ending, setEnding] = useState({ endDate: today, reason: '' });
  const gangChange = useGangChange();
  const [change, setChange] = useState<ChangeDraft | null>(null);
  const changeWhy =
    change &&
    ((change.role === 'JOIN' ? !change.code.trim() : change.employeeId === null) || !reasonOk(change.reason))
      ? 'm09.why.move'
      : null;
  const mayTransfer = can(me.data, 'M-02', 'EDIT');
  const moveWhy =
    move && (move.toGangId === null || !move.from || !reasonOk(move.reason)) ? 'm09.why.move' : null;
  const mayCreate = can(me.data, 'M-09', 'CREATE');
  const mayEdit = can(me.data, 'M-09', 'EDIT');
  // DoD 4: the End-date button names what is missing (UI #30).
  const endWhy = !mayEdit
    ? 'reason.hrOnly'
    : !ending.endDate
      ? 'm09.why.endDate'
      : !reasonOk(ending.reason)
        ? 'm09.why.reason'
        : null;
  const gangWhy = gang ? gangBlocked(gang) : null;
  const strengthWhy = mayEdit
    ? strengthBlocked(newStrength.strength, newStrength.effectiveFrom, newStrength.reason, today)
    : 'reason.hrOnly';

  function startGang() {
    setError(null);
    setGang({
      depotLocationId: depot ?? options.data?.depots[0]?.id ?? 0,
      payrollType: 'DEPT_LABOUR',
      gangName: '',
      sanctionedStrength: null,
      effectiveFrom: today,
      members: [],
      reason: '',
    });
  }

  useEffect(() => {
    if (startNew) startGang();
    // Once, when the drawn New Gang mounts the register.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  /** Finds a labour member by code (M-02 register) and adds it with the chosen role. */
  async function addMember(gang: NewGang) {
    setError(null);
    setMissing(null);
    try {
      const match = await findLabour(gang.payrollType, labourCode);
      if (!match) {
        setMissing(labourCode.trim().toUpperCase());
        return;
      }
      setNames({ ...names, [match.employeeId]: `${match.labourCode} - ${match.name}` });
      setGang({ ...gang, members: [...gang.members, { employeeId: match.employeeId, role }] });
      setLabourCode('');
    } catch (cause) {
      setError(cause);
    }
  }

  async function sendGang(gang: NewGang) {
    setError(null);
    try {
      await createGang.mutateAsync(gang);
      toast(t('m09.sent'));
      setGang(null);
    } catch (cause) {
      setError(cause);
    }
  }

  async function sendStrength(selected: GangRow) {
    setError(null);
    try {
      await strength.mutateAsync({ id: selected.gangId, ...newStrength });
      toast(t('m09.sent'));
    } catch (cause) {
      setError(cause);
    }
  }

  async function sendMove(move: MoveDraft, toGangId: number) {
    setError(null);
    try {
      await moveMember.mutateAsync({
        kind: move.kind,
        employeeId: move.member.employeeId,
        toGangId,
        from: move.from,
        to: move.kind === 'ATTACH' && move.to ? move.to : null,
        reason: move.reason,
      });
      toast(t('m09.moveSent'));
      setMove(null);
    } catch (cause) {
      setError(cause);
    }
  }

  async function sendChange(change: ChangeDraft, selected: GangRow) {
    setError(null);
    setMissing(null);
    try {
      let employeeId = change.employeeId;
      if (change.role === 'JOIN') {
        const found = await findLabour(selected.payrollType ?? 'DEPT_LABOUR', change.code);
        if (!found) {
          setMissing(change.code.trim().toUpperCase());
          return;
        }
        employeeId = found.employeeId;
      }
      if (employeeId === null) return;
      await gangChange.mutateAsync({
        gangId: selected.gangId,
        role: change.role,
        employeeId,
        from: change.from,
        reason: change.reason,
      });
      toast(t('m09.moveSent'));
      setChange(null);
    } catch (cause) {
      setError(cause);
    }
  }

  async function sendEnd(selected: GangRow) {
    setError(null);
    try {
      await endGang.mutateAsync({
        gangId: selected.gangId,
        endDate: ending.endDate,
        reason: ending.reason.trim(),
      });
      toast(t('m09.moveSent'));
    } catch (cause) {
      setError(cause);
    }
  }

  return (
    <div className="wireframe-body-container">
      <Card title={t('screen.M-09')}>
        <div className="toolbar">
          <label>
            {t('m02.depot')}{' '}
            <select
              value={depot ?? ''}
              onChange={(e) => setDepot(e.target.value ? Number(e.target.value) : null)}
            >
              <option value="">{t('m09.allDepots')}</option>
              {(options.data?.depots ?? []).map((d) => (
                <option key={d.id} value={d.id}>
                  {d.code} - {d.name}
                </option>
              ))}
            </select>
          </label>
          <button
            type="button"
            className="btn primary"
            disabled={!mayCreate}
            title={mayCreate ? undefined : t('reason.hrOnly')}
            onClick={startGang}
          >
            {t('action.m09.create')}
          </button>
        </div>
        <ServerPagination
          queryKey={labourKeys.gangs(depot)}
          fetchPage={gangsPage(depot)}
          empty={<Empty text={t('m09.none')} />}
        >
          {(rows) => (
            <table className="t" aria-label={t('screen.M-09')}>
              <thead>
                <tr>
                  <th>
                    {t('m09.gang')}
                    <Src s="ENT" />
                  </th>
                  <th>
                    {t('m09.gangCode')}
                    <Src s="SYS" />
                  </th>
                  <th>
                    {t('m02.depot')}
                    <Src s="ENT" />
                  </th>
                  <th>
                    {t('m09.type')}
                    <Src s="SYS" />
                  </th>
                  <th>
                    {t('m09.strength')}
                    <Src s="ENT" />
                  </th>
                  <th>
                    {t('m09.members')}
                    <Src s="CALC" />
                  </th>
                  <th>
                    {t('m09.from')}
                    <Src s="ENT" />
                  </th>
                  <th>
                    {t('m09.to')}
                    <Src s="ENT" />
                  </th>
                  <th>
                    {t('m09.statusCol')}
                    <Src s="SYS" />
                  </th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {rows.map((g) => (
                  <tr key={g.gangId}>
                    <td>{g.gangName}</td>
                    <td>{g.gangCode}</td>
                    <td>{g.depot}</td>
                    <td>{t(`m09.type.${g.gangType}`)}</td>
                    <td>{g.sanctionedStrength ?? '—'}</td>
                    <td>{g.members}</td>
                    <td>{date(g.effectiveFrom)}</td>
                    <td>{g.effectiveTo ? date(g.effectiveTo) : '—'}</td>
                    <td>{t(`m09.status.${g.status}`)}</td>
                    <td>
                      <button type="button" className="btn" onClick={() => setSelected(g)}>
                        {t('m09.open')}
                      </button>
                      <HistoryButton
                        entity="labour_gang"
                        recordKey={g.gangCode}
                        label={`${g.gangCode} · ${g.gangName}`}
                      />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </ServerPagination>
      </Card>
      {selected && (
        <Card title={t('m09.membersOf', [selected.gangName])}>
          <div className="toolbar">
            <label>
              {t('m09.asOf')}{' '}
              <input type="date" value={asOf} onChange={(e) => setAsOf(e.target.value || today)} />
            </label>
          </div>
          {/* SRS-2-48: the Gang Strength Report; the Ancillary Pool has no strength. */}
          {selected.gangType === 'HANDLING' && (
            <GangStrengthReport key={selected.gangId} gangId={selected.gangId} />
          )}
          {members.data && (
            <table className="t" aria-label={t('m09.membersOf', [selected.gangName])}>
              <thead>
                <tr>
                  <th>
                    {t('m02.code')}
                    <Src s="SYS" />
                  </th>
                  <th>
                    {t('m02.name')}
                    <Src s="ENT" />
                  </th>
                  <th>
                    {t('m09.role')}
                    <Src s="ENT" />
                  </th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {members.data.map((m) => (
                  <tr key={m.employeeId}>
                    <td>{m.labourCode}</td>
                    <td>{m.name}</td>
                    <td>{t(`m09.role.${m.role}`)}</td>
                    <td>
                      {(m.role === 'HANDLING' || m.role === 'ANCILLARY') && (
                        <>
                          <button
                            type="button"
                            className="btn"
                            disabled={!mayTransfer}
                            title={mayTransfer ? undefined : t('reason.hrOnly')}
                            onClick={() =>
                              setMove({
                                kind: 'TRANSFER',
                                member: m,
                                toGangId: null,
                                from: today,
                                to: '',
                                reason: '',
                              })
                            }
                          >
                            {t('action.m02.transfer')}
                          </button>
                          <button
                            type="button"
                            className="btn"
                            disabled={!mayEdit}
                            title={mayEdit ? undefined : t('reason.hrOnly')}
                            onClick={() =>
                              setMove({
                                kind: 'ATTACH',
                                member: m,
                                toGangId: null,
                                from: today,
                                to: '',
                                reason: '',
                              })
                            }
                          >
                            {t('action.m09.attach')}
                          </button>
                        </>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {selected.gangType === 'HANDLING' && selected.status === 'ACTIVE' && (
            <>
              <h4>{t('m09.newStrength')}</h4>
              <div className="toolbar">
                <label>
                  {t('m09.strength')}{' '}
                  <input
                    type="number"
                    min={1}
                    max={9999}
                    value={newStrength.strength}
                    onChange={(e) => setNewStrength({ ...newStrength, strength: Number(e.target.value) })}
                  />
                </label>
                <label>
                  {t('m09.from')}{' '}
                  <input
                    type="date"
                    value={newStrength.effectiveFrom}
                    onChange={(e) => setNewStrength({ ...newStrength, effectiveFrom: e.target.value })}
                  />
                </label>
                <label>
                  {t('m02.reason')}{' '}
                  <TextAreaField
                    maxLength={500}
                    value={newStrength.reason}
                    onChange={(e) => setNewStrength({ ...newStrength, reason: e.target.value })}
                  />
                </label>
                <button
                  type="button"
                  className="btn primary"
                  disabled={strengthWhy !== null || strength.isPending}
                  title={strengthWhy ? t(strengthWhy) : undefined}
                  onClick={() => void sendStrength(selected)}
                >
                  {t('action.m09.strength')}
                </button>
              </div>
              <h4>{t('action.m09.endDate')}</h4>
              <div className="toolbar">
                <label>
                  {t('m09.endDateLabel')}{' '}
                  <input
                    type="date"
                    value={ending.endDate}
                    onChange={(e) => setEnding({ ...ending, endDate: e.target.value })}
                  />
                </label>
                <label>
                  {t('m09.endReason')}{' '}
                  <TextAreaField
                    maxLength={500}
                    value={ending.reason}
                    onChange={(e) => setEnding({ ...ending, reason: e.target.value })}
                  />
                </label>
                <button
                  type="button"
                  className="btn"
                  disabled={endWhy !== null || endGang.isPending}
                  title={endWhy ? t(endWhy) : undefined}
                  onClick={() => void sendEnd(selected)}
                >
                  {t('action.m09.endDate')}
                </button>
              </div>
            </>
          )}
          {selected.status === 'ACTIVE' && (
            <div className="toolbar">
              <button
                type="button"
                className="btn"
                disabled={!mayEdit}
                title={mayEdit ? undefined : t('reason.hrOnly')}
                onClick={() =>
                  setChange({ role: 'JOIN', code: '', employeeId: null, from: today, reason: '' })
                }
              >
                {t('action.m09.join')}
              </button>
              {selected.gangType === 'HANDLING' && (
                <button
                  type="button"
                  className="btn"
                  disabled={!mayEdit}
                  title={mayEdit ? undefined : t('reason.hrOnly')}
                  onClick={() =>
                    setChange({ role: 'SARDAR', code: '', employeeId: null, from: today, reason: '' })
                  }
                >
                  {t('action.m09.leader')}
                </button>
              )}
            </div>
          )}
          {change && (
            <div className="form-grid">
              {change.role === 'JOIN' ? (
                <label>
                  {t('m09.joinLabour')}
                  <Src s="ENT" />
                  <input
                    maxLength={20}
                    value={change.code}
                    onChange={(e) => setChange({ ...change, code: e.target.value })}
                  />
                </label>
              ) : (
                <>
                  <label>
                    {t('m09.leaderRole')}
                    <Src s="ENT" />
                    <select
                      value={change.role}
                      onChange={(e) => setChange({ ...change, role: e.target.value as 'SARDAR' | 'MANDAL' })}
                    >
                      <option value="SARDAR">{t('m09.role.SARDAR')}</option>
                      <option value="MANDAL">{t('m09.role.MANDAL')}</option>
                    </select>
                  </label>
                  <label>
                    {t('m09.leaderMember')}
                    <Src s="ENT" />
                    <select
                      value={change.employeeId ?? ''}
                      onChange={(e) =>
                        setChange({ ...change, employeeId: e.target.value ? Number(e.target.value) : null })
                      }
                    >
                      <option value="">—</option>
                      {(members.data ?? [])
                        .filter((m) => m.role === 'HANDLING')
                        .map((m) => (
                          <option key={m.employeeId} value={m.employeeId}>
                            {m.labourCode} - {m.name}
                          </option>
                        ))}
                    </select>
                  </label>
                </>
              )}
              <label>
                {change.role === 'JOIN' ? t('m09.joinFrom') : t('m09.leaderFrom')}
                <Src s="ENT" />
                <input
                  type="date"
                  value={change.from}
                  onChange={(e) => setChange({ ...change, from: e.target.value })}
                />
              </label>
              <label>
                {t('m09.changeReason')}
                <Src s="ENT" />
                <TextAreaField
                  maxLength={500}
                  value={change.reason}
                  onChange={(e) => setChange({ ...change, reason: e.target.value })}
                />
              </label>
              <div className="toolbar">
                <button
                  type="button"
                  className="btn primary"
                  disabled={changeWhy !== null || gangChange.isPending}
                  title={changeWhy ? t(changeWhy) : undefined}
                  onClick={() => void sendChange(change, selected)}
                >
                  {t('action.m09.sendMove')}
                </button>
                <button type="button" className="btn" onClick={() => setChange(null)}>
                  {t('common.cancel')}
                </button>
              </div>
              {missing && <p className="sub2">{t('m09.notFound', [missing])}</p>}
            </div>
          )}
          {move && move.kind === 'TRANSFER' && (
            <TransferForm
              key={move.member.employeeId}
              member={{
                employeeId: move.member.employeeId,
                labourCode: move.member.labourCode,
                payrollType: selected.payrollType ?? '',
                gangId: selected.gangId,
              }}
              onDone={() => setMove(null)}
            />
          )}
          {move && move.kind === 'ATTACH' && (
            <div className="form-grid">
              <h4>{t(`m09.move.${move.kind}`, [move.member.labourCode])}</h4>
              {/* SRS-2-31: a target gang of the member's payroll type, searched on the server. */}
              <GangPicker
                label={t('m09.toGang')}
                source="ENT"
                value={move.toGangId}
                exclude={selected.gangId}
                search={{
                  status: 'ACTIVE',
                  gangType: 'HANDLING',
                  payrollType: selected.payrollType ?? undefined,
                }}
                onChange={(g) => setMove({ ...move, toGangId: g ? g.gangId : null })}
              />
              <label>
                {t('m09.attachFrom')}
                <Src s="ENT" />
                <input
                  type="date"
                  value={move.from}
                  onChange={(e) => setMove({ ...move, from: e.target.value })}
                />
              </label>
              <label>
                {t('m09.attachTo')}
                <Src s="ENT" />
                <input
                  type="date"
                  value={move.to}
                  onChange={(e) => setMove({ ...move, to: e.target.value })}
                />
              </label>
              <label>
                {t('m09.moveReason')}
                <Src s="ENT" />
                <TextAreaField
                  maxLength={500}
                  value={move.reason}
                  onChange={(e) => setMove({ ...move, reason: e.target.value })}
                />
              </label>
              <div className="toolbar">
                <button
                  type="button"
                  className="btn primary"
                  disabled={moveWhy !== null || moveMember.isPending}
                  title={moveWhy ? t(moveWhy) : undefined}
                  onClick={() => void sendMove(move, Number(move.toGangId))}
                >
                  {t('action.m09.sendMove')}
                </button>
                <button type="button" className="btn" onClick={() => setMove(null)}>
                  {t('common.cancel')}
                </button>
              </div>
            </div>
          )}
        </Card>
      )}
      {gang && (
        <Card title={t('m09.new')}>
          <div className="form-grid">
            <label>
              {t('m02.depot')}
              <Src s="ENT" />
              <select
                value={gang.depotLocationId}
                onChange={(e) => setGang({ ...gang, depotLocationId: Number(e.target.value), members: [] })}
              >
                {(options.data?.depots ?? []).map((d) => (
                  <option key={d.id} value={d.id}>
                    {d.code} - {d.name}
                  </option>
                ))}
              </select>
            </label>
            <label>
              {t('m02.payrollType')}
              <Src s="ENT" />
              <select
                value={gang.payrollType}
                onChange={(e) =>
                  setGang({ ...gang, payrollType: e.target.value as NewGang['payrollType'], members: [] })
                }
              >
                {PAYROLL_TYPES.map((p) => (
                  <option key={p} value={p}>
                    {t(`labour.type.${p}`)}
                  </option>
                ))}
              </select>
            </label>
            <label>
              {t('m09.gang')}
              <Src s="ENT" />
              <input
                maxLength={150}
                value={gang.gangName}
                onChange={(e) => setGang({ ...gang, gangName: e.target.value })}
              />
            </label>
            <label>
              {t('m09.strength')}
              <Src s="ENT" />
              <input
                type="number"
                min={1}
                max={9999}
                value={gang.sanctionedStrength ?? ''}
                onChange={(e) =>
                  setGang({ ...gang, sanctionedStrength: e.target.value ? Number(e.target.value) : null })
                }
              />
            </label>
            <label>
              {t('m09.from')}
              <Src s="ENT" />
              <input
                type="date"
                value={gang.effectiveFrom}
                onChange={(e) => setGang({ ...gang, effectiveFrom: e.target.value })}
              />
            </label>
            <label>
              {t('m02.reason')}
              <Src s="ENT" />
              <TextAreaField
                maxLength={500}
                value={gang.reason}
                onChange={(e) => setGang({ ...gang, reason: e.target.value })}
              />
            </label>
          </div>
          <div className="toolbar">
            <label>
              {t('m02.code')}{' '}
              <input maxLength={20} value={labourCode} onChange={(e) => setLabourCode(e.target.value)} />
            </label>
            <label>
              {t('m09.role')}{' '}
              <select value={role} onChange={(e) => setRole(e.target.value as GangRole)}>
                {GANG_ROLES.filter((r) => r !== 'ANCILLARY').map((r) => (
                  <option key={r} value={r}>
                    {t(`m09.role.${r}`)}
                  </option>
                ))}
              </select>
            </label>
            <button
              type="button"
              className="btn"
              disabled={labourCode.trim() === ''}
              onClick={() => void addMember(gang)}
            >
              {t('action.m09.addMember')}
            </button>
          </div>
          {missing && <p className="sub2">{t('m09.notFound', [missing])}</p>}
          <ul aria-label={t('m09.members')}>
            {gang.members.map((m) => (
              <li key={m.employeeId}>
                {names[m.employeeId] ?? m.employeeId} - {t(`m09.role.${m.role}`)}{' '}
                <button
                  type="button"
                  className="btn"
                  onClick={() =>
                    setGang({ ...gang, members: gang.members.filter((x) => x.employeeId !== m.employeeId) })
                  }
                >
                  {t('action.m09.removeMember')}
                </button>
              </li>
            ))}
          </ul>
          <div className="toolbar">
            <button
              type="button"
              className="btn primary"
              disabled={gangWhy !== null || createGang.isPending}
              title={gangWhy ? t(gangWhy) : undefined}
              onClick={() => void sendGang(gang)}
            >
              {t('action.m09.send')}
            </button>
            <button type="button" className="btn" onClick={() => setGang(null)}>
              {t('common.cancel')}
            </button>
          </div>
          {gangWhy && <p className="sub2">{t(gangWhy)}</p>}
        </Card>
      )}
      <Problem error={error} />
    </div>
  );
}
