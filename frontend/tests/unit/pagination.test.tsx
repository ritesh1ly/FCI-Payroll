import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { type ReactNode, useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { I18nProvider } from '@/shared/i18n';
import type { Me } from '@/shared/session';
import {
  FALLBACK_PAGING,
  pageCount,
  pageRange,
  pageWindow,
  type PageResult,
  ServerPagination,
  useLocalPage,
} from '@/shared/ui';
import { Tbl } from '@/shared/ui/wireframe';
import { me, t } from './support/harness';

/**
 * Pagination of every list (owner requirement 2026-10-24, docs/plan/X-pagination.md): only the
 * shown page is fetched, 20 rows to start; page-index selection (numbers and "Go to page"), previous
 * / next, first / last; the rows per page from /auth/me (10, 20, 50, 100).
 */

function wrap(children: ReactNode, profile?: Me) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  if (profile) client.setQueryData(['me'], profile);
  return (
    <QueryClientProvider client={client}>
      <I18nProvider>{children}</I18nProvider>
    </QueryClientProvider>
  );
}

const rows = (n: number) => Array.from({ length: n }, (_, i) => `row-${i + 1}`);

function server(total: number) {
  return vi.fn(async (page: number, size: number): Promise<PageResult<string>> => ({
    items: rows(total).slice(page * size, page * size + size),
    page,
    size,
    total,
  }));
}

function list(fetchPage: (page: number, size: number) => Promise<PageResult<string>>, profile?: Me) {
  return wrap(
    <ServerPagination queryKey={['test-server']} fetchPage={fetchPage}>
      {(items) => (
        <ul>
          {items.map((r) => (
            <li key={r}>{r}</li>
          ))}
        </ul>
      )}
    </ServerPagination>,
    profile,
  );
}

describe('page arithmetic', () => {
  it('SRS_NF_3_countsPagesNeverFewerThanOne', () => {
    expect(pageCount(0, 20)).toBe(1);
    expect(pageCount(20, 20)).toBe(1);
    expect(pageCount(21, 20)).toBe(2);
    expect(pageCount(-3, 0)).toBe(1);
  });

  it('SRS_NF_3_givesTheFirstAndLastRowOfAPage', () => {
    expect(pageRange(0, 20, 0)).toEqual([0, 0]);
    expect(pageRange(0, 20, 230)).toEqual([1, 20]);
    expect(pageRange(11, 20, 230)).toEqual([221, 230]);
    expect(pageRange(1, 50, 100)).toEqual([51, 100]);
  });

  it('SRS_NF_3_showsTheFirstLastAndTwoNeighboursWithGaps', () => {
    expect(pageWindow(0, 1)).toEqual([0]);
    expect(pageWindow(0, 4)).toEqual([0, 1, 2, 3]);
    expect(pageWindow(5, 20)).toEqual([0, 'gap', 3, 4, 5, 6, 7, 'gap', 19]);
    expect(pageWindow(0, 20)).toEqual([0, 1, 2, 'gap', 19]);
    expect(pageWindow(19, 20)).toEqual([0, 'gap', 17, 18, 19]);
    expect(pageWindow(3, 7)).toEqual([0, 1, 2, 3, 4, 5, 6]);
    expect(FALLBACK_PAGING).toEqual({ sizes: [10, 20, 50, 100], defaultSize: 20 });
  });
});

describe('ServerPagination', () => {
  it('SRS_NF_3_fetchesTwentyRowsFirstThenAPageByNumberNextAndPrevious', async () => {
    const fetchPage = server(230);
    render(list(fetchPage, me([])));
    expect(await screen.findByText('row-1')).toBeTruthy();
    expect(fetchPage).toHaveBeenLastCalledWith(0, 20);
    expect(screen.getAllByRole('listitem')).toHaveLength(20);
    expect(screen.getByText(t('page.range', [1, 20, 230]))).toBeTruthy();
    expect(screen.getByRole('button', { name: t('page.number', [1]) })).toHaveAttribute(
      'aria-current',
      'page',
    );

    // Page index selection: the number of a page.
    await userEvent.click(screen.getByRole('button', { name: t('page.number', [3]) }));
    expect(await screen.findByText('row-41')).toBeTruthy();
    expect(fetchPage).toHaveBeenLastCalledWith(2, 20);
    await userEvent.click(screen.getByRole('button', { name: t('page.next') }));
    expect(await screen.findByText('row-61')).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('page.prev') }));
    expect(await screen.findByText('row-41')).toBeTruthy();

    // "Go to page": any page, also one the buttons leave out.
    const goTo = screen.getByLabelText(t('page.goTo'));
    await userEvent.selectOptions(goTo, within(goTo).getByRole('option', { name: '10' }));
    expect(await screen.findByText('row-181')).toBeTruthy();
    expect(fetchPage).toHaveBeenLastCalledWith(9, 20);
    expect(screen.getAllByLabelText(t('page.gap')).length).toBeGreaterThan(0);

    await userEvent.click(screen.getByRole('button', { name: t('page.last') }));
    expect(await screen.findByText('row-230')).toBeTruthy();
    expect(screen.getAllByRole('listitem')).toHaveLength(10);
    expect(screen.getByRole('button', { name: t('page.next') })).toBeDisabled();
    expect(screen.getByRole('button', { name: t('page.last') })).toBeDisabled();
    await userEvent.click(screen.getByRole('button', { name: t('page.first') }));
    expect(await screen.findByText('row-1')).toBeTruthy();
    expect(screen.getByRole('button', { name: t('page.prev') })).toBeDisabled();
  });

  it('SRS_NF_3_aLargerPageStartsAgainAtPageOneAndScrollsInsideItsCard', async () => {
    const fetchPage = server(230);
    const { container } = render(list(fetchPage, me([])));
    await screen.findByText('row-1');
    await userEvent.click(screen.getByRole('button', { name: t('page.number', [2]) }));
    await screen.findByText('row-21');
    const sizes = screen.getByLabelText(t('page.size'));
    expect(
      within(sizes)
        .getAllByRole('option')
        .map((o) => o.textContent),
    ).toEqual(['10', '20', '50', '100']);
    await userEvent.selectOptions(sizes, '100');
    await waitFor(() => expect(fetchPage).toHaveBeenLastCalledWith(0, 100));
    expect(await screen.findByText('row-100')).toBeTruthy();
    expect(screen.getAllByRole('listitem')).toHaveLength(100);
    expect(container.querySelector('.fl-table-scroll')).not.toBeNull();
    expect(screen.getByText(t('page.range', [1, 100, 230]))).toBeTruthy();
  });

  it('SRS_NF_3_theSizesAndTheStartingSizeComeFromTheSession', async () => {
    const fetchPage = server(30);
    render(list(fetchPage, me([], { paging: { sizes: [10, 20, 50], defaultSize: 10 } })));
    expect(await screen.findByText('row-10')).toBeTruthy();
    expect(fetchPage).toHaveBeenLastCalledWith(0, 10);
    const sizes = screen.getByLabelText(t('page.size'));
    expect(
      within(sizes)
        .getAllByRole('option')
        .map((o) => o.textContent),
    ).toEqual(['10', '20', '50']);
  });

  it('SRS_NF_3_withoutSessionPagingTheOwnersValuesApply', async () => {
    const fetchPage = server(5);
    render(list(fetchPage));
    expect(await screen.findByText('row-5')).toBeTruthy();
    expect(fetchPage).toHaveBeenLastCalledWith(0, 20);
    // One page: no "Go to page".
    expect(screen.queryByLabelText(t('page.goTo'))).toBeNull();
  });

  it('SRS_NF_3_aListThatShrinksUnderTheShownPageGoesBackToItsLastPage (UI audit S9)', async () => {
    let total = 41;
    const fetchPage = vi.fn(async (page: number, size: number): Promise<PageResult<string>> => ({
      items: rows(total).slice(page * size, page * size + size),
      page,
      size,
      total,
    }));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
    client.setQueryData(['me'], me([]));
    render(
      <QueryClientProvider client={client}>
        <I18nProvider>
          <ServerPagination queryKey={['shrinking']} fetchPage={fetchPage}>
            {(items) => (
              <ul>
                {items.map((r) => (
                  <li key={r}>{r}</li>
                ))}
              </ul>
            )}
          </ServerPagination>
        </I18nProvider>
      </QueryClientProvider>,
    );
    expect(await screen.findByText('row-1')).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('page.number', [3]) }));
    expect(await screen.findByText('row-41')).toBeTruthy();
    // The last row of page 3 is decided elsewhere: 40 rows are left, two pages.
    total = 40;
    await client.invalidateQueries({ queryKey: ['shrinking'] });
    expect(await screen.findByText('row-21')).toBeTruthy();
    expect(fetchPage).toHaveBeenLastCalledWith(1, 20);
    expect(screen.getByText(t('page.range', [21, 40, 40]))).toBeTruthy();
    // Every row gone: the first page, empty, never a page past the end.
    total = 0;
    await client.invalidateQueries({ queryKey: ['shrinking'] });
    expect(await screen.findByText(t('page.empty'))).toBeTruthy();
    await waitFor(() => expect(fetchPage).toHaveBeenLastCalledWith(0, 20));
    // Exactly full pages (equal boundary) keep the page.
    total = 20;
    await client.invalidateQueries({ queryKey: ['shrinking'] });
    expect(await screen.findByText('row-20')).toBeTruthy();
  });

  it('SRS_NF_3_anEmptyListSaysSo', async () => {
    render(
      wrap(
        <ServerPagination
          queryKey={['test-empty']}
          fetchPage={async () => ({ items: [], page: 0, size: 20, total: 0 })}
        >
          {() => null}
        </ServerPagination>,
        me([]),
      ),
    );
    expect(await screen.findByText(t('page.empty'))).toBeTruthy();
    expect(screen.getByText(t('page.range', [0, 0, 0]))).toBeTruthy();
  });

  it('SRS_NF_3_theServersRefusalIsShown', async () => {
    const { ApiError } = await import('@/shared/api');
    render(
      wrap(
        <ServerPagination
          queryKey={['test-error']}
          fetchPage={async () => {
            throw new ApiError(
              400,
              { errorCode: 'COM-400-PAGE', messageKey: 'error.COM-400-PAGE', args: [] },
              undefined,
            );
          }}
        >
          {() => null}
        </ServerPagination>,
        me([]),
      ),
    );
    expect(await screen.findByText(t('error.COM-400-PAGE'))).toBeTruthy();
  });
});

/** A screen holding its whole (bounded) list, with a filter that shortens it. */
function Held({ total }: { total: number }) {
  const [short, setShort] = useState(false);
  const shown = useLocalPage(rows(short ? 5 : total), short ? 'short' : 'all');
  return (
    <>
      <button type="button" onClick={() => setShort(true)}>
        filter
      </button>
      <ul>
        {shown.rows.map((r) => (
          <li key={r}>{r}</li>
        ))}
      </ul>
      {shown.pager}
    </>
  );
}

describe('useLocalPage', () => {
  it('SRS_NF_3_aHeldListShowsTwentyRowsAndMovesByNumberSizeAndFilter', async () => {
    render(wrap(<Held total={45} />, me([])));
    expect(screen.getAllByRole('listitem')).toHaveLength(20);
    expect(screen.getByText(t('page.range', [1, 20, 45]))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('page.number', [3]) }));
    expect(screen.getAllByRole('listitem')).toHaveLength(5);
    expect(screen.getByText('row-41')).toBeTruthy();

    // A larger page starts again at page one.
    await userEvent.selectOptions(screen.getByLabelText(t('page.size')), '50');
    expect(screen.getAllByRole('listitem')).toHaveLength(45);
    expect(screen.getByText(t('page.range', [1, 45, 45]))).toBeTruthy();

    // Back on a later page, a filter returns to the first page.
    await userEvent.selectOptions(screen.getByLabelText(t('page.size')), '10');
    await userEvent.click(screen.getByRole('button', { name: t('page.last') }));
    expect(screen.getByText('row-41')).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: 'filter' }));
    await waitFor(() => expect(screen.getByText(t('page.range', [1, 5, 5]))).toBeTruthy());
    expect(screen.getAllByRole('listitem')).toHaveLength(5);
  });

  it('SRS_NF_3_insideAWireframeTableTheStatesAndThePagerAreRowsSpanningItsColumns', async () => {
    // A list inside the kit's table body (OD-121): loading, empty and pager are rows, never a block in tbody.
    let release: () => void = () => undefined;
    const slow = vi.fn(
      (page: number, size: number) =>
        new Promise<PageResult<string>>((done) => {
          release = () => done({ items: page === 0 && size > 0 ? ['row-1'] : [], page, size, total: 1 });
        }),
    );
    const { container } = render(
      wrap(
        <Tbl title="Register" cols={[{ t: 'A' }, { t: 'B' }, { t: 'C' }]}>
          <ServerPagination queryKey={['test-in-table']} fetchPage={slow}>
            {(items) =>
              items.map((r) => (
                <tr key={r}>
                  <td>{r}</td>
                </tr>
              ))
            }
          </ServerPagination>
        </Tbl>,
        me([]),
      ),
    );
    const loading = await waitFor(() => {
      const row = container.querySelector('tbody tr.fl-state-loading td');
      if (!row) throw new Error('no loading row');
      return row;
    });
    expect(loading.getAttribute('colspan')).toBe('3');
    release();
    expect(await screen.findByText('row-1')).toBeTruthy();
    // The rows are the table's own: no scroll wrapper inside tbody, the pager a spanning row.
    expect(container.querySelector('tbody .fl-table-scroll')).toBeNull();
    expect(container.querySelector('tbody tr.fl-state-pager td')?.getAttribute('colspan')).toBe('3');
  });

  it('SRS_NF_3_anEmptyPageAndAFailureInsideAWireframeTableAreSpanningRows', async () => {
    const empty = vi.fn(async (page: number, size: number) => ({ items: [], page, size, total: 0 }));
    const { container, unmount } = render(
      wrap(
        <Tbl title="Register" cols={[{ t: 'A' }, { t: 'B' }]}>
          <ServerPagination queryKey={['test-empty-table']} fetchPage={empty}>
            {() => null}
          </ServerPagination>
        </Tbl>,
        me([]),
      ),
    );
    expect(await screen.findByText(t('page.empty'))).toBeTruthy();
    expect(container.querySelector('tbody tr.fl-state-empty td')?.getAttribute('colspan')).toBe('2');
    unmount();
    const failing = vi.fn(async () => {
      throw new Error('boom');
    });
    const failed = render(
      wrap(
        <Tbl title="Register" cols={[{ t: 'A' }]}>
          <ServerPagination queryKey={['test-failing-table']} fetchPage={failing}>
            {() => null}
          </ServerPagination>
        </Tbl>,
        me([]),
      ),
    );
    await waitFor(() => expect(failed.container.querySelector('tbody tr.fl-state-error')).not.toBeNull());
  });
});
