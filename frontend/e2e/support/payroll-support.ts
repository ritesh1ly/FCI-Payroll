import { type Browser, expect, type Locator, type Page } from '@playwright/test';
import { businessToday, t, workAs } from './app';

/**
 * Helpers of the Module 01 specs (06-exceptions-and-adjustments/). They run after 05-payroll-processing/03-payroll-cycle (one worker,
 * file order), which leaves the current period of the cycle test office (DT001, support/cycle-office.ts, OD-141) computed
 * and month-end LOCKED: the locked period I-20 and I-23 need, with that office's Maker and Checker. DO Delhi HQ (DH001)
 * keeps locked demo months up to the month before the current one (V082_21) and its current month free; X-06 queries
 * refer to DH001's newest locked month. Run alone, the checks that need the test office's lock skip with the reason.
 * MAKER / CHECKER below are the Delhi users of the specs that read DH001.
 */

export const MAKER = 'maker.delhi@fci-demo.test';
export const CHECKER = 'ddo.delhi@fci-demo.test';
export const maker = { role: 'PAYROLL_MAKER', office: 'DH001' };
export const checker = { role: 'PAYROLL_CHECKER', office: 'DH001' };

/** A minimal PDF: the server checks the magic bytes, not the extension. */
export const PDF = {
  name: 'approval-note.pdf',
  mimeType: 'application/pdf',
  buffer: Buffer.from('%PDF-1.7\n%e2e\n'),
};

/**
 * Opens the closed Cycle actions section of C-02 (Wireframes v6.1 draws these actions on C-01 and D-01; OD-121): the
 * address keeps it open across a reload.
 */
export async function openCycleActions(page: Page) {
  const section = page.locator('#c02-cycle');
  if (!(await section.evaluate((d) => (d as HTMLDetailsElement).open)))
    await section.locator('summary').click();
}

/** The session's current period (YYYY-MM) and the state of its cycle at the session's office. */
export async function currentCycle(
  page: Page,
): Promise<{ period: string; yymm: string; status: string | null }> {
  return page.evaluate(async () => {
    const me = (await (await fetch('/nginx/fci-payroll/auth/me', { credentials: 'same-origin' })).json()) as {
      period: string;
    };
    const r = await fetch(`/nginx/fci-payroll/v1/cycles/${me.period}`, { credentials: 'same-origin' });
    const cycle = r.ok ? ((await r.json()) as { status: string | null }) : { status: null };
    return { period: me.period, yymm: me.period.replace('-', ''), status: cycle.status };
  });
}

/** A JSON call the signed-in page makes itself (CSRF and idempotency headers as the app sends them). */
export async function apiJson<T>(page: Page, path: string, method = 'GET', body?: unknown): Promise<T> {
  return page.evaluate(
    async ([p, m, b]) => {
      const token =
        document.cookie
          .split('; ')
          .find((c) => c.startsWith('XSRF-TOKEN='))
          ?.split('=')[1] ?? '';
      const r = await fetch(`/nginx/fci-payroll${p}`, {
        method: m as string,
        credentials: 'same-origin',
        headers: {
          'Content-Type': 'application/json',
          'X-XSRF-TOKEN': token,
          'Idempotency-Key': crypto.randomUUID(),
        },
        body: b === undefined ? undefined : JSON.stringify(b),
      });
      return (r.status === 204 ? null : await r.json()) as unknown;
    },
    [path, method, body] as const,
  ) as Promise<T>;
}

/**
 * Every row of a paged list endpoint (docs/plan/X-pagination.md), read page by page at 100 rows (the
 * largest size the server offers).
 */
export async function apiAll<T>(page: Page, path: string): Promise<T[]> {
  const join = path.includes('?') ? '&' : '?';
  const rows: T[] = [];
  for (let n = 0; ; n++) {
    const view = await apiJson<{ items: T[]; total: number }>(page, `${path}${join}page=${n}&size=100`);
    rows.push(...view.items);
    if ((n + 1) * 100 >= view.total) return rows;
  }
}

/**
 * Leaves no open record of an earlier, interrupted run in the way (re-runnable suite): cancels with
 * a reason every record of {@code eis} on the list at {@code listPath} that may still be cancelled.
 */
export async function cancelLeftovers(page: Page, listPath: string, eis: string, open: string[]) {
  const rows = await apiAll<{ id: number; eis: string | null; status: string }>(page, listPath);
  for (const r of rows.filter((x) => x.eis === eis && open.includes(x.status))) {
    await apiJson(page, `${listPath}/${r.id}/cancel`, 'POST', { reason: 'E2E: left over by an earlier run' });
  }
}

/** YYYYMM as the screens show it: "SEP 2026". */
export function monthLabel(yymm: string): string {
  const names = ['JAN', 'FEB', 'MAR', 'APR', 'MAY', 'JUN', 'JUL', 'AUG', 'SEP', 'OCT', 'NOV', 'DEC'];
  return `${names[Number(yymm.slice(4)) - 1]} ${yymm.slice(0, 4)}`;
}

/** The YYYYMM {@code back} months before {@code yymm}. */
export function monthsBefore(yymm: string, back: number): string {
  const d = new Date(Date.UTC(Number(yymm.slice(0, 4)), Number(yymm.slice(4)) - 1 - back, 1));
  return `${d.getUTCFullYear()}${String(d.getUTCMonth() + 1).padStart(2, '0')}`;
}

/** The success toast (role=status) with a text matching {@code text}. */
export async function expectToast(page: Page, text: RegExp | string): Promise<string> {
  const toast = page.getByRole('status').filter({ hasText: text }).last();
  await expect(toast).toBeVisible();
  return (await toast.textContent()) ?? '';
}

/**
 * The number a toast built from the message `key` carries in its placeholder {0} (e.g. e01s.submitted, "Request {0}
 * submitted ..."). Never match a toast on a bare /\d+/: other live regions are role=status too (E-01's
 * "0 selected: ..." bulk counter, OD-149) and the first number found was then 0 (full run 2026-11-09).
 */
export async function toastNumber(page: Page, key: string): Promise<string> {
  const marker = 'NUMBERMARKER';
  const escaped = t('en', key, [marker]).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const pattern = new RegExp(escaped.replace(marker, '(\\d+)'));
  const text = await expectToast(page, pattern);
  return pattern.exec(text)?.[1] ?? '';
}

/** Fills the order particulars of an order card (SRS-G-30) and its file. */
export async function fillOrder(page: Page, scope: Locator | Page = page) {
  await scope.getByLabel(t('en', 'ord.type')).selectOption('OTHER');
  await scope.getByLabel(t('en', 'ord.date')).fill(businessToday());
  await scope.getByLabel(t('en', 'ord.authority')).fill('AGM (Pay) Delhi');
  await scope.getByLabel(t('en', 'ord.file')).setInputFiles(PDF);
}

/** The table row of a record by its reference (rows carry data-reference). */
export function row(page: Page, reference: string): Locator {
  return page.locator(`tr[data-reference="${reference}"]`);
}

/** The invoice-table row of an I-20 / I-23 record (rows carry data-invoice). */
export function invoiceRow(page: Page, reference: string): Locator {
  return page.locator(`tr[data-invoice="${reference}"]`);
}

/** The order card of a record ("Order copy - attach & store - {reference}"). */
export function orderCard(page: Page, reference: string): Locator {
  return page
    .locator('.card')
    .filter({ has: page.getByRole('heading', { name: t('en', 'ord.cardTitle', [reference]) }) });
}

/** The drawn order card of a screen (OD-121): one per screen, showing the selected record. */
export function drawnOrderCard(page: Page): Locator {
  return page.locator('[data-order-card]');
}

/**
 * The particulars of the drawn order card, then its file: picking the file stores it for the selected
 * record, or holds it for the next request on I-23 (WF-078, SRS-G-16, SRS-G-30).
 */
export async function fillDrawnOrder(page: Page) {
  const card = drawnOrderCard(page);
  await card.locator('select').first().selectOption('OTHER');
  await card.locator('input[type="date"]').fill(businessToday());
  await card.getByPlaceholder(t('en', 'wf.ord.officePlaceholder')).fill('AGM (Pay) Delhi');
  await card.locator('input[type="file"]').setInputFiles(PDF);
}

/** Opens 👁 View of a register row (drawn row action): the record and, for the Maker, its submission. */
export async function viewRecord(page: Page, reference: string): Promise<Locator> {
  await row(page, reference)
    .getByRole('button', { name: `${t('en', 'common.view')} ${reference}` })
    .click();
  return page.getByRole('dialog');
}

/**
 * Cancels a record with a reason through its Cancel button: the drawn ⊘ Cancel of the invoice table for
 * I-20 and I-23 (cancellation is an invoice decision, BR-AUDIT-010), "Cancel…" in the register row for X-07.
 */
export async function cancelWithReason(
  page: Page,
  reference: string,
  reason: string,
  where: 'invoice' | 'register' = 'invoice',
) {
  const button =
    where === 'invoice'
      ? invoiceRow(page, reference).getByRole('button', { name: t('en', 'wf.inv.cancel') })
      : row(page, reference).getByRole('button', { name: t('en', 'gen.cancelRecord') });
  await button.click();
  const dialog = page.getByRole('dialog');
  await dialog.getByRole('textbox').fill(reason);
  await dialog.getByRole('button').last().click();
}

/** One row of the A-05 audit trail (GET /v1/admin/audit). */
export interface AuditRow {
  changedBy: string;
  entityName: string;
  entityKey: string;
  fieldName: string;
  oldValue: string | null;
  newValue: string | null;
  reason: string | null;
  approvalId: number | null;
  changeType: string;
  moduleCode: string | null;
  sourceIp: string | null;
}

/**
 * The audit rows of one record as the HQ Super User reads them on A-05 (SRS-G-2): today's trail of
 * `entity`, filtered to `key`. Opens its own page, so the caller's session is untouched.
 */
export async function auditTrail(browser: Browser, entity: string, key: string): Promise<AuditRow[]> {
  return (await auditRows(browser, entity)).filter((r) => r.entityKey === key);
}

/**
 * Every audit row of `entity` written in the last day, as the HQ Super User reads them on A-05: for
 * a record whose key the browser does not know (a generated id), filter by its reason or values.
 */
export async function auditRows(browser: Browser, entity: string): Promise<AuditRow[]> {
  const auditor = await browser.newPage();
  try {
    await workAs(
      auditor,
      'en',
      'superuser.hq@fci-demo.test',
      { role: 'SUPER_USER', office: 'HQ001' },
      'A-05',
    );
    const from = new Date(Date.now() - 86_400_000).toISOString();
    const to = new Date(Date.now() + 60_000).toISOString();
    const query = `/nginx/fci-payroll/v1/admin/audit?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}&entity=${entity}&size=100`;
    const result = await auditor.evaluate(async (q) => {
      const r = await fetch(q, { credentials: 'same-origin' });
      return { status: r.status, body: await r.text() };
    }, query);
    expect(result.status, `GET ${query}: ${result.body.slice(0, 300)}`).toBe(200);
    const page = JSON.parse(result.body) as { items: AuditRow[] };
    return page.items;
  } finally {
    await auditor.close();
  }
}
