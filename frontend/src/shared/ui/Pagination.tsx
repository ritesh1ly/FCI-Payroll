'use client';

import { keepPreviousData, type QueryKey, useQuery } from '@tanstack/react-query';
import { type ReactNode, useContext, useEffect, useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { useMe } from '@/shared/session';
import { Problem } from './primitives';
import { TableColumns } from './tableContext';

/**
 * Pagination of every list (SRS-NF-3 "open lists and searches page by page"; owner requirement
 * 2026-10-24, docs/plan/X-pagination.md): the server
 * returns one page; the user picks a page by its number, moves with previous / next (and first /
 * last), and chooses the rows per page from the sizes the A-07 settings offer (10, 20, 50, 100;
 * 20 to start), served in /auth/me.
 */

/** One page of a server list: the API's common shape ({@code Paged}, page numbers from 0). */
export interface PageResult<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

/** The sizes and starting size when /auth/me has not said (the owner's values). */
export const FALLBACK_PAGING = { sizes: [10, 20, 50, 100], defaultSize: 20 } as const;

/** The page sizes of this session: from /auth/me, else the owner's values. */
export function usePageSizes(): { sizes: readonly number[]; defaultSize: number } {
  const me = useMe();
  return me.data?.paging ?? FALLBACK_PAGING;
}

/** Number of pages for {@code total} rows (at least one, so "page 1 of 1" for an empty list). */
export function pageCount(total: number, size: number): number {
  return Math.max(1, Math.ceil(Math.max(0, total) / Math.max(1, size)));
}

/** The 1-based first and last row shown on {@code page} (0, 0 for an empty list). */
export function pageRange(page: number, size: number, total: number): [number, number] {
  if (total <= 0) return [0, 0];
  const first = page * size + 1;
  return [Math.min(first, total), Math.min(first + size - 1, total)];
}

/**
 * The page buttons to show (0-based): the first and last page, the current one with two neighbours
 * on each side, and 'gap' where pages are left out ("1 … 4 5 [6] 7 8 … 20").
 */
export function pageWindow(page: number, pages: number): (number | 'gap')[] {
  const shown = new Set<number>([0, pages - 1]);
  for (let p = page - 2; p <= page + 2; p++) if (p >= 0 && p < pages) shown.add(p);
  const sorted = [...shown].sort((a, b) => a - b);
  const out: (number | 'gap')[] = [];
  sorted.forEach((p, i) => {
    const previous = sorted[i - 1];
    if (previous !== undefined && p - previous > 1) out.push('gap');
    out.push(p);
  });
  return out;
}

/**
 * The pager: "21–40 of 230", first / previous, the page numbers, next / last, a "Go to page"
 * choice and the rows per page. Pages are numbered from 0 inside, from 1 on screen.
 */
export function Pager({
  page,
  size,
  total,
  onPage,
  onSize,
  busy = false,
}: {
  page: number;
  size: number;
  total: number;
  onPage: (page: number) => void;
  onSize?: (size: number) => void;
  busy?: boolean;
}) {
  const { t } = useI18n();
  const { sizes } = usePageSizes();
  const pages = pageCount(total, size);
  const [first, last] = pageRange(page, size, total);
  return (
    <nav className="fl-pager" aria-label={t('page.nav')} aria-busy={busy}>
      <span className="fl-pager-range" aria-live="polite">
        {t('page.range', [first, last, total])}
      </span>
      <div className="fl-pager-buttons">
        <button type="button" className="btn" disabled={busy || page <= 0} onClick={() => onPage(0)}>
          {t('page.first')}
        </button>
        <button type="button" className="btn" disabled={busy || page <= 0} onClick={() => onPage(page - 1)}>
          {t('page.prev')}
        </button>
        <span className="fl-pager-pages">
          {pageWindow(page, pages).map((p, i) =>
            p === 'gap' ? (
              <span key={`gap-${i}`} className="fl-pager-gap" aria-label={t('page.gap')}>
                …
              </span>
            ) : (
              <button
                key={p}
                type="button"
                className="btn sm"
                aria-label={t('page.number', [p + 1])}
                aria-current={p === page ? 'page' : undefined}
                disabled={busy || p === page}
                onClick={() => onPage(p)}
              >
                {p + 1}
              </button>
            ),
          )}
        </span>
        <span className="fl-pager-of">{t('page.of', [page + 1, pages])}</span>
        <button
          type="button"
          className="btn"
          disabled={busy || page + 1 >= pages}
          onClick={() => onPage(page + 1)}
        >
          {t('page.next')}
        </button>
        <button
          type="button"
          className="btn"
          disabled={busy || page + 1 >= pages}
          onClick={() => onPage(pages - 1)}
        >
          {t('page.last')}
        </button>
      </div>
      {pages > 1 && (
        <label className="fl-pager-goto">
          {t('page.goTo')}
          <select value={page} disabled={busy} onChange={(e) => onPage(Number(e.target.value))}>
            {Array.from({ length: pages }, (_, p) => (
              <option key={p} value={p}>
                {p + 1}
              </option>
            ))}
          </select>
        </label>
      )}
      {onSize && (
        <label className="fl-pager-size">
          {t('page.size')}
          <select value={size} disabled={busy} onChange={(e) => onSize(Number(e.target.value))}>
            {sizes.map((n) => (
              <option key={n} value={n}>
                {n}
              </option>
            ))}
          </select>
        </label>
      )}
    </nav>
  );
}

/**
 * Pages rows the screen already holds: a bounded list that must arrive whole because the screen also
 * derives its filters, tiles or form choices from it (M-08 catalogue, M-16 values;
 * docs/plan/X-pagination.md). Back to the first page when {@code resetKey} (the filters) changes.
 */
export function useLocalPage<T>(rows: readonly T[], resetKey: string): { rows: T[]; pager: ReactNode } {
  const { defaultSize } = usePageSizes();
  const [page, setPage] = useState(0);
  const [size, setSize] = useState<number | null>(null);
  const shownSize = size ?? defaultSize;
  useEffect(() => setPage(0), [resetKey]);
  // A shorter list (a filter, a withdrawn row) never leaves the screen past its last page.
  const shownPage = Math.min(page, pageCount(rows.length, shownSize) - 1);
  return {
    rows: rows.slice(shownPage * shownSize, (shownPage + 1) * shownSize),
    pager: (
      <Pager
        page={shownPage}
        size={shownSize}
        total={rows.length}
        onPage={setPage}
        onSize={(n) => {
          setSize(n);
          setPage(0);
        }}
      />
    ),
  };
}

/**
 * Server-side pagination: only the rows of the shown page are fetched — every list screen uses it.
 * {@code fetchPage} calls the API with page and size; the page goes back to the first when {@code
 * queryKey} (the filters) or the size changes. The previous page stays on screen while the next one
 * loads; a long page scrolls inside the card under a sticky header.
 */
export function ServerPagination<R extends PageResult<unknown>>({
  queryKey,
  fetchPage,
  personal = true,
  children,
  empty,
  onTotal,
  onRows,
}: {
  queryKey: QueryKey;
  fetchPage: (page: number, size: number) => Promise<R>;
  /** Personal data: never kept in the browser's query cache (PERSONAL_DATA). */
  personal?: boolean;
  /** The page's rows, and the whole answer for a list that carries more (totals, tiles). */
  children: (rows: R['items'], page: R) => ReactNode;
  empty?: ReactNode;
  /** Told the total whenever it changes (a count in the screen's title). */
  onTotal?: (total: number) => void;
  /** Told the rows of the shown page (a screen that also looks a row up, or draws a second table). */
  onRows?: (rows: R['items']) => void;
}) {
  const { t } = useI18n();
  const { defaultSize } = usePageSizes();
  // Inside a wireframe-kit table body (Tbl): states and pager become rows spanning its columns.
  const columns = useContext(TableColumns);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState<number | null>(null);
  const shownSize = size ?? defaultSize;
  const filterKey = JSON.stringify(queryKey);
  useEffect(() => setPage(0), [filterKey]);
  const query = useQuery({
    queryKey: [...queryKey, 'page', page, shownSize],
    queryFn: () => fetchPage(page, shownSize),
    placeholderData: keepPreviousData,
    ...(personal ? { staleTime: 0, gcTime: 0 } : {}),
  });
  const result = query.data;
  const total = result?.total;
  useEffect(() => {
    if (total !== undefined) onTotal?.(total);
  }, [total, onTotal]);
  const items = result?.items;
  useEffect(() => {
    if (items) onRows?.(items);
  }, [items, onRows]);
  // UI audit S9: after an action shrinks the list (the last row of the last page approved,
  // revoked, ...), go back to the last page that still has rows, never "page 3 of 2".
  const lastPage = total === undefined ? 0 : Math.max(0, Math.ceil(total / shownSize) - 1);
  useEffect(() => {
    if (total !== undefined && !query.isFetching && page > lastPage) setPage(lastPage);
  }, [total, page, lastPage, query.isFetching]);
  // A state (loading, error, empty) or the pager, as a row of the enclosing table when inside one.
  const spanning = (node: ReactNode, key: string) =>
    columns === null ? (
      node
    ) : (
      <tr className={`fl-state-row fl-state-${key}`}>
        <td colSpan={columns}>{node}</td>
      </tr>
    );
  if (query.isPending) return spanning(<p className="fl-hint">{t('common.loading')}</p>, 'loading');
  // The server's own reason (outside the scope, no permission …), not a generic message.
  if (query.isError || !result)
    return spanning(<Problem error={query.error ?? new Error('page')} />, 'error');
  // A list without rows in its answer is shown as empty, never as a crash.
  const rows: R['items'] = Array.isArray(result.items) ? result.items : [];
  const body =
    rows.length === 0 ? (
      spanning(empty ?? <p className="fl-hint">{t('page.empty')}</p>, 'empty')
    ) : columns === null ? (
      <div className="fl-table-scroll">{children(rows, result)}</div>
    ) : (
      children(rows, result)
    );
  return (
    <>
      {body}
      {spanning(
        <Pager
          page={page}
          size={shownSize}
          total={result.total}
          busy={query.isFetching}
          onPage={setPage}
          onSize={(n) => {
            setSize(n);
            setPage(0);
          }}
        />,
        'pager',
      )}
    </>
  );
}
