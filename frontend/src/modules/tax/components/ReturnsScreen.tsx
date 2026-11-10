'use client';

import { useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { useReference } from '@/shared/reference';
import { can, useMe } from '@/shared/session';
import { businessToday, recentFinYears } from '@/shared/time';
import { amount, date, Problem, ServerPagination, withBase } from '@/shared/ui';
import { RegisterSection } from '@/shared/ui/masterParts';
import {
  Act,
  Explained,
  Locked,
  Panel,
  RowActs,
  Tbl,
  Tiles,
  Toolbar,
  ViewDialog,
  WfTile,
} from '@/shared/ui/wireframe';
import type { ReturnLine } from '../api/screensApi';
import { lastCompletedQuarter, maskedToken, returnSteps } from '../business/screenRules';
import { screenKeys, screenPages, useReturnSummary } from '../orchestration/screensQueries';
import { StatutoryReturns } from './StatutoryReturns';

/** The return's quarter as the pack prints it: Q1 FY27. */
function quarterLabel(l: ReturnLine): string {
  return `${l.quarter} FY${l.finYear.slice(5)}`;
}

/**
 * C-11 Statutory Returns - Generation & Validation as Wireframes v6.1 draws it (pack screen c9, OD-121; SRS-7-7,
 * SRS-7-8, SRS-7-13, SRS-7-21, SRS-7-23, SRS-7-31): the last completed quarter opens first, with where it stands in the
 * eight steps, the register with its masked token and the rules. Challans, generation, validation, release and the
 * filing record stay whole in the closed register. Filing itself is outside the system (Annexure A.8).
 */
export function ReturnsScreen() {
  const { t } = useI18n();
  const me = useMe();
  const { options } = useReference();
  const opening = lastCompletedQuarter(businessToday());
  const years = recentFinYears(2);
  const [finYear, setFinYear] = useState(opening.finYear);
  const [quarter, setQuarter] = useState(opening.quarter);
  const [unit, setUnit] = useState('');
  const [query, setQuery] = useState('');
  const [open, setOpen] = useState(false);
  const [viewing, setViewing] = useState<ReturnLine | null>(null);
  const summary = useReturnSummary(finYear, quarter);
  const s = summary.data;
  const mayCreate = can(me.data, 'C-11', 'CREATE');
  const checker = can(me.data, 'C-11', 'APPROVE');
  const show = () => setOpen(true);
  const steps = returnSteps(s);
  const pill = (key: string) => <span className="pill">{t(key)}</span>;
  const needle = query.trim().toLowerCase();
  const unmapped = (s?.challans ?? 0) - (s?.challansMapped ?? 0);
  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sp" />
        {mayCreate ? (
          <Act label={t('txw.c11.import')} onClick={show} />
        ) : (
          <Locked label={t('txw.c11.import')} why={t('txw.c10.why.maker')} />
        )}
        {mayCreate ? (
          <Act label={t('txw.c11.generate')} kind="pri" onClick={show} />
        ) : (
          <Locked label={t('txw.c11.generate')} why={t('txw.c11.why.generate')} />
        )}
      </div>
      <Toolbar
        search={query}
        onSearch={setQuery}
        exportControl={<Explained label={t('wf.export')} why={t('txw.why.noExport')} />}
      >
        <select
          aria-label={t('txw.fy', [finYear])}
          value={finYear}
          onChange={(e) => setFinYear(e.target.value)}
        >
          {years.map((y) => (
            <option key={y} value={y}>
              {t('txw.fy', [y])}
            </option>
          ))}
        </select>
        <select
          aria-label={t('txw.c11.quarter')}
          value={quarter}
          onChange={(e) => setQuarter(e.target.value)}
        >
          {options('TAX_RETURN_QUARTER').map((o) => (
            <option key={o.value} value={o.value}>
              {t(`txw.c11.q.${o.value}`)}
            </option>
          ))}
        </select>
        <select aria-label={t('txw.c11.allUnits')} value={unit} onChange={(e) => setUnit(e.target.value)}>
          <option value="">{t('txw.c11.allUnits')}</option>
          {(s?.units ?? []).map((u) => (
            <option key={u.id} value={u.id}>
              {u.code} · {u.name}
            </option>
          ))}
        </select>
      </Toolbar>
      <Problem error={summary.error} />
      <Tiles>
        <WfTile
          icon="👥"
          label={t('txw.c11.tile.deductees')}
          src="CALC"
          big={s?.deductees ?? 0}
          sub={t('txw.c11.tile.deducteesSub', [s?.staff ?? 0, s?.labour ?? 0])}
        />
        <WfTile
          icon="🧾"
          label={t('txw.c11.tile.tds')}
          src="CALC"
          big={s?.tds == null ? '—' : `₹ ${amount(s.tds)}`}
          sub={t('txw.c11.tile.tdsSub', [quarter, finYear])}
          tone="ok"
        />
        <WfTile
          icon="🏦"
          label={t('txw.c11.tile.challans')}
          src="ENT"
          big={`${s?.challansMapped ?? 0} / ${s?.challans ?? 0}`}
          sub={t('txw.c11.tile.challansSub', [unmapped])}
          tone="warn"
        />
        <WfTile
          icon="⚠️"
          label={t('txw.c11.tile.pan')}
          src="SYS"
          big={s?.panExceptions ?? 0}
          sub={t('txw.c11.tile.panSub')}
          tone="bad"
          bad={(s?.panExceptions ?? 0) > 0}
        />
      </Tiles>
      <Panel
        title={t('txw.c11.steps')}
        act={<Act label={t('txw.c11.exceptions')} kind="sm" href={withBase('/payroll/?s=X-05')} />}
        items={[
          {
            icon: '①',
            tone: 'ok',
            title: t('txw.c11.s.1'),
            src: 'RULE',
            text: t('txw.c11.s.1s', [s?.definitionVersion ?? 0, date(s?.definitionFrom)]),
            right: pill(steps[0] ?? ''),
          },
          {
            icon: '②',
            tone: 'ok',
            title: t('txw.c11.s.2'),
            src: 'CALC',
            text: t('txw.c11.s.2s'),
            right: pill(steps[1] ?? ''),
          },
          {
            icon: '③',
            tone: 'warn',
            title: t('txw.c11.s.3'),
            src: 'ENT',
            text: t('txw.c11.s.3s', [unmapped]),
            right: <span className="pill">{`${s?.challansMapped ?? 0} / ${s?.challans ?? 0}`}</span>,
          },
          {
            icon: '④',
            tone: 'bad',
            title: t('txw.c11.s.4'),
            src: 'SYS',
            text: t('txw.c11.s.4s', [s?.panExceptions ?? 0]),
            right: <span className="pill">{s?.panExceptions ?? 0}</span>,
          },
          {
            icon: '⑤',
            tone: 'info',
            title: t('txw.c11.s.5'),
            src: 'SYS',
            text: t('txw.c11.s.5s'),
            right: pill(steps[4] ?? ''),
          },
          {
            icon: '⑥',
            tone: 'pur',
            title: t('txw.c11.s.6'),
            src: 'SYS',
            text: t('txw.c11.s.6s'),
            right: pill(steps[5] ?? ''),
          },
          {
            icon: '⑦',
            tone: 'gray',
            title: t('txw.c11.s.7'),
            src: 'SYS',
            text: t('txw.c11.s.7s'),
            right: pill(steps[6] ?? ''),
          },
          {
            icon: '⑧',
            tone: 'info',
            title: t('txw.c11.s.8'),
            src: 'ENT',
            text: t('txw.c11.s.8s'),
            right: pill(steps[7] ?? ''),
          },
        ]}
      />
      <Tbl
        title={t('txw.c11.register')}
        label={t('txw.c11.registerLabel')}
        acts={
          <>
            <Explained label={t('txw.c11.download')} kind="sm" why={t('txw.c11.why.download')} />
            <Act label={t('txw.c11.log')} kind="sm" onClick={show} />
          </>
        }
        cols={[
          { t: t('txw.c11.col.quarter'), s: 'SYS' },
          { t: t('txw.c11.col.type'), s: 'RULE' },
          { t: t('txw.c11.col.deductees'), s: 'CALC', num: true },
          { t: t('txw.c11.col.tds'), s: 'CALC', num: true },
          { t: t('txw.c11.col.validated'), s: 'SYS' },
          { t: t('txw.c11.col.filed'), s: 'ENT' },
          { t: t('txw.c11.col.token'), s: 'ENT' },
          { t: t('txw.c11.col.status'), s: 'SYS' },
          { t: t('txw.c11.col.actions'), s: 'SYS' },
        ]}
      >
        <ServerPagination
          queryKey={screenKeys.returnLines(unit)}
          fetchPage={screenPages.returnLines(unit)}
          empty={t('txw.c11.none')}
        >
          {(page) =>
            page
              .filter((l) => needle === '' || quarterLabel(l).toLowerCase().includes(needle))
              .map((l) => (
                <tr key={l.id} data-reference={String(l.id)}>
                  <td>{quarterLabel(l)}</td>
                  <td>{t(l.correction ? 'txw.c11.correction' : 'txw.c11.original')}</td>
                  <td className="num">{l.deductees}</td>
                  <td className="num">{l.tds === null ? '—' : `₹ ${amount(l.tds)}`}</td>
                  <td>{t(l.validated ? 'txw.c11.passed' : 'txw.c11.notRun')}</td>
                  <td>{date(l.filed)}</td>
                  <td>{maskedToken(l.token)}</td>
                  <td>
                    <span className="pill">{t(`c11.status.${l.status}`)}</span>
                  </td>
                  <td>
                    <RowActs
                      viewName={`${t('common.view')} ${quarterLabel(l)}`}
                      editName={`${t('common.edit')} ${quarterLabel(l)}`}
                      onView={() => setViewing(l)}
                      onEdit={mayCreate ? show : undefined}
                      editWhy={t('txw.why.makerInputs')}
                      onApprove={
                        checker ? () => window.location.assign(withBase('/payroll/?s=W-01')) : undefined
                      }
                    />
                  </td>
                </tr>
              ))
          }
        </ServerPagination>
      </Tbl>
      <Panel
        title={t('txw.c11.rules')}
        items={[
          { icon: '🔒', tone: 'bad', title: t('txw.c11.r.1'), src: 'RULE', text: t('txw.c11.r.1s') },
          { icon: '🧮', tone: 'warn', title: t('txw.c11.r.2'), src: 'RULE', text: t('txw.c11.r.2s') },
          { icon: '🆔', tone: 'info', title: t('txw.c11.r.3'), src: 'HRMS', text: t('txw.c11.r.3s') },
          { icon: '♻️', tone: 'ok', title: t('txw.c11.r.4'), src: 'RULE', text: t('txw.c11.r.4s') },
          { icon: '🚫', tone: 'gray', title: t('txw.c11.r.5'), src: 'RULE', text: t('txw.c11.r.5s') },
        ]}
      />
      <RegisterSection label={t('txw.c11.section')} open={open} onToggle={setOpen} id="c11-register">
        {open && <StatutoryReturns />}
      </RegisterSection>
      {viewing && (
        <ViewDialog
          screen={t('screen.C-11')}
          onClose={() => setViewing(null)}
          rows={[
            { k: t('txw.c11.col.quarter'), v: quarterLabel(viewing), s: 'SYS' },
            { k: t('txw.c11.col.deductees'), v: viewing.deductees, s: 'CALC' },
            { k: t('txw.c11.col.tds'), v: amount(viewing.tds), s: 'CALC' },
            { k: t('txw.c11.col.filed'), v: date(viewing.filed), s: 'ENT' },
            { k: t('txw.c11.col.token'), v: maskedToken(viewing.token), s: 'ENT' },
            { k: t('txw.c11.col.status'), v: t(`c11.status.${viewing.status}`), s: 'SYS' },
          ]}
        />
      )}
    </div>
  );
}
