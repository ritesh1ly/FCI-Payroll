import { expect, test } from '@playwright/test';
import { apiStatus, businessToday, decide, t, workAs } from '../support/app';
import { openCapture } from '../support/labour';
import { CHECKER, MAKER, apiJson, checker, maker } from '../support/payroll-support';

/**
 * BMAD UI review fixes of the labour screens (SRS-2-30, SRS-2-46, SRS-2-48, SRS-2-15; I-01 l.4541 /
 * l.4545, I-02 l.4621 / l.4632):
 * - M-02: an amendment opens with the record as it stands (PAN, account and mobile masked, left empty
 *   to keep them) and sends only what changed, so a new bank account never clears the PAN, the PF
 *   flag, the CPF code or the UAN; an amendment that changes nothing is not sent; the Payroll Maker
 *   never amends.
 * - I-01: the gang is searched on the server and the depot's Ancillary Pool is offered (no strength
 *   check for it); a handling gang's day shows its present strength against the Sanctioned Strength.
 * - I-02: the pool's slip offers no bag-based operation; an amendment opens with the saved rows.
 *
 * Demo data V052_9 (Okhla Gang 1, its Ancillary Pool), V077_16 (L10703 in the pool), V054_9
 * (operations). Re-runnable: the member's name, PAN, CPF code and UAN and the slip's day and hour come
 * from the run number.
 */
const HR = 'hr.delhi@fci-demo.test';
const LDH = 'ldh.depot@fci-demo.test';
const hr = { role: 'ESTABLISHMENT_HR', office: 'DH001' };
const ldh = { role: 'LABOUR_DEALING_HAND', office: 'DP-DL01' };

const run = String(Math.floor(Math.random() * 9000) + 1000);
const letters = 'ABCDEFGHJKLMNPQRSTUVWXYZ';
const tag =
  letters.charAt(Number(run) % letters.length) +
  letters.charAt(Math.floor(Number(run) / 24) % letters.length);
const name = `Amendoo${tag}`;
const pan = `DMOPF${run}K`;
const cpf = `DL/F${run}`;
const uan = `1003${run}${run}`;
const label = (key: string) => new RegExp(`^${t('en', key).replace(/[.*+?^${}()|[\]\\/]/g, '\\$&')}`);

interface Detail {
  employeeId: number;
  panMasked: string | null;
  bankAccountMasked: string | null;
  pfApplicable: boolean;
  cpfCode: string | null;
  uanNumber: string | null;
  fatherName: string | null;
}

async function memberId(page: import('@playwright/test').Page): Promise<number> {
  const found = await apiJson<{ items: { employeeId: number }[] }>(
    page,
    `/v1/labour/members?text=${encodeURIComponent(name)}&size=10`,
  );
  return found.items[0]?.employeeId ?? 0;
}

test.describe.serial('Labour pickers and amendments (BMAD fixes)', () => {
  test(
    'SRS-2-30: HR records a member with PF, CPF code and UAN; the Payroll Checker approves it',
    { tag: ['@SRS-2-30', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', HR, hr, 'M-02');
      const options = await apiJson<{
        depots: { id: number; code: string }[];
        designations: { id: number; code: string }[];
      }>(page, '/v1/labour/options');
      const made = await apiJson<{ id: number }>(page, '/v1/labour/members', 'POST', {
        payrollType: 'DEPT_LABOUR',
        firstName: name,
        lastName: 'Demo',
        fatherName: 'Hari Demo',
        gender: 'M',
        birthDate: '1987-05-20',
        joiningDate: '2012-07-01',
        designationId: options.designations.find((d) => d.code === 'HL')?.id,
        depotLocationId: options.depots.find((d) => d.code === 'DP-DL01')?.id,
        panNumber: pan,
        ifsc: 'SBIN0DEMO01',
        bankAccount: `00008${run}`,
        mobile: '9000034001',
        pfApplicable: true,
        cpfCode: cpf,
        uanNumber: uan,
        reason: `E2E BMAD appointment ${run}`,
      });
      expect(made.id).toBeGreaterThan(0);
      await workAs(page, 'en', CHECKER, checker, 'W-01');
      await decide(page, new RegExp(`E2E BMAD appointment ${run}`), 'APPROVE');
    },
  );

  test(
    'SRS-2-30: the amendment opens with the record and a new bank account keeps PAN, PF, CPF code and UAN',
    { tag: ['@SRS-2-30', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', HR, hr, 'M-02');
      await page.getByLabel(label('m02.search')).fill(name);
      const table = page.getByRole('table', { name: t('en', 'screen.M-02') });
      await table
        .getByRole('button', { name: t('en', 'action.m02.amend') })
        .first()
        .click();
      await expect(page.getByLabel(label('m02.fatherName'))).toHaveValue('Hari Demo');
      await expect(page.getByLabel(label('m02.cpfCode'))).toHaveValue(cpf);
      await expect(page.getByLabel(label('m02.uan'))).toHaveValue(uan);
      await expect(page.getByLabel(label('m02.ifsc'))).toHaveValue('SBIN0DEMO01');
      await expect(page.getByRole('checkbox', { name: t('en', 'm02.pfApplicable') })).toBeChecked();
      // R-PII: PAN and account are masked hints; the inputs stay empty and are kept.
      await expect(page.getByLabel(label('m02.pan'))).toHaveValue('');
      await expect(page.getByText(t('en', 'm02.keepMasked', [`******${pan.slice(6)}`]))).toBeVisible();
      const send = page.getByRole('button', { name: t('en', 'action.m02.send') });
      await page.getByLabel(label('m02.reason')).fill(`E2E BMAD bank change ${run}`);
      // Nothing changed yet: the amendment is not sent, with the reason shown.
      await expect(send).toBeDisabled();
      await expect(send).toHaveAttribute('title', t('en', 'm02.why.noChange'));
      await page.getByLabel(label('m02.bank')).fill(`00007${run}`);
      await expect(send).toBeEnabled();
      await send.click();
      await expect(page.getByText(t('en', 'm02.sent'))).toBeVisible();

      await workAs(page, 'en', CHECKER, checker, 'W-01');
      await decide(page, new RegExp(`E2E BMAD bank change ${run}`), 'APPROVE');

      await workAs(page, 'en', HR, hr, 'M-02');
      const after = await apiJson<Detail>(page, `/v1/labour/members/${await memberId(page)}`);
      expect(after.bankAccountMasked?.endsWith(run)).toBe(true);
      // BMAD TR-035 follow-up (SRS l.754, BR-SEC-001): the PAN shows its last four only.
      expect(after.panMasked).toBe(`******${pan.slice(6)}`);
      expect(after.pfApplicable).toBe(true);
      expect(after.cpfCode).toBe(cpf);
      expect(after.uanNumber).toBe(uan);
      expect(after.fatherName).toBe('Hari Demo');
    },
  );

  test(
    'SRS-2-30: an amendment that changes nothing is refused; the Payroll Maker never amends',
    { tag: ['@SRS-2-30', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', HR, hr, 'M-02');
      const id = await memberId(page);
      expect(
        await apiStatus(page, `/v1/labour/members/${id}/amendments`, 'POST', {
          uanNumber: uan,
          reason: 'E2E nothing new',
        }),
      ).toBe(422);
      await workAs(page, 'en', MAKER, maker, 'M-02');
      await page.getByLabel(label('m02.search')).fill(name);
      const table = page.getByRole('table', { name: t('en', 'screen.M-02') });
      await expect(table.getByRole('button', { name: t('en', 'action.m02.amend') }).first()).toBeDisabled();
      expect(
        await apiStatus(page, `/v1/labour/members/${id}/amendments`, 'POST', {
          bankAccount: '000012345678',
          reason: 'E2E',
        }),
      ).toBe(403);
    },
  );

  test(
    'SRS-2-46 / SRS-2-48: I-01 offers the Ancillary Pool and checks a gang day against its strength',
    { tag: ['@SRS-2-46', '@SRS-2-48', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', LDH, ldh, 'I-01');
      await openCapture(page, 'I-01');
      const gang = page.getByRole('combobox', { name: label('m09.gang') });
      const today = businessToday();
      await gang.selectOption({
        label: `DP-DL01-G001 - Ancillary Pool (${t('en', 'm09.type.ANCILLARY_POOL')})`,
      });
      await page.getByRole('button', { name: t('en', 'i01.markDay', [today]) }).click();
      const day = page.getByRole('table', { name: t('en', 'i01.dayOf', [today]) });
      await expect(day.getByText(/L10703/)).toBeVisible();
      // The pool has no Sanctioned Strength: no strength check.
      await expect(page.getByRole('status', { name: t('en', 'i01.strengthCheck') })).toHaveCount(0);

      await page.getByLabel(t('en', 'm09.gangSearch')).fill('okhla');
      await gang.selectOption({ label: 'DP-DL01-G002 - Okhla Gang 1' });
      await page.getByRole('button', { name: t('en', 'i01.markDay', [today]) }).click();
      const check = page.getByRole('status', { name: t('en', 'i01.strengthCheck') });
      await expect(check).toContainText(/against Sanctioned Strength \d+/);
    },
  );

  test(
    'SRS-2-46 / I-02 l.4621: the pool slip offers no bag-based operation',
    { tag: ['@SRS-2-46', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', LDH, ldh, 'I-02');
      await openCapture(page, 'I-02');
      await page.getByRole('combobox', { name: label('m09.gang') }).selectOption({
        label: `DP-DL01-G001 - Ancillary Pool (${t('en', 'm09.type.ANCILLARY_POOL')})`,
      });
      await page
        .getByTestId('i02-capture')
        .getByRole('button', { name: t('en', 'action.i02.new') })
        .click();
      const operation = page.getByLabel(label('i02.operation'));
      await expect(operation.locator('option', { hasText: 'W2P' })).toHaveCount(0);
      await expect(operation.locator('option', { hasText: 'PBNW' })).toHaveCount(1);
    },
  );

  test(
    'SRS-2-15: an I-02 amendment opens with the saved rows of the slip',
    { tag: ['@SRS-2-15', '@SRS-2-4', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', LDH, ldh, 'I-02');
      await openCapture(page, 'I-02');
      // A day two months back the run picks, so a rerun takes another day and hour.
      const [y, m] = businessToday().split('-').map(Number) as [number, number];
      const back = new Date(Date.UTC(y, m - 3, 1));
      const month = back.toISOString().slice(0, 7);
      const date = `${month}-${String((Number(run) % 20) + 3).padStart(2, '0')}`;
      const gangs = await apiJson<{ items: { gangId: number }[] }>(
        page,
        '/v1/labour/gangs?text=DP-DL01-G002',
      );
      const gangId = gangs.items[0]?.gangId ?? 0;
      const members = await apiJson<{ employeeId: number }[]>(
        page,
        `/v1/labour/gangs/${gangId}/members?date=${date}`,
      );
      // SRS-2-3: the day's attendance before its work-slip (a day frozen by an earlier run answers 422).
      await apiStatus(page, `/v1/labour/gangs/${gangId}/attendance-days`, 'POST', {
        date,
        marks: members.map((x) => ({ employeeId: x.employeeId, code: 'W' })),
      });
      const view = await apiJson<{ operations: { id: number; code: string }[]; bagTypes: { id: number }[] }>(
        page,
        `/v1/labour/gangs/${gangId}/work-slips?month=${month}`,
      );
      const w2p = view.operations.find((o) => o.code === 'W2P')?.id;
      const hour = String(6 + (Number(run) % 12)).padStart(2, '0');
      const end = String(7 + (Number(run) % 12)).padStart(2, '0');
      const slip = await apiJson<{ number: string }>(page, `/v1/labour/gangs/${gangId}/work-slips`, 'POST', {
        date,
        continueWork: false,
        rows: [
          {
            operationId: w2p,
            bagTypeId: view.bagTypes[0]?.id,
            bags: 345,
            stackHeight: 8,
            start: `${hour}:15`,
            end: `${end}:15`,
          },
        ],
        participants: members.map((x) => x.employeeId),
      });
      expect(slip.number).toBeTruthy();

      await page
        .getByRole('combobox', { name: label('m09.gang') })
        .selectOption({ label: 'DP-DL01-G002 - Okhla Gang 1' });
      await page.getByLabel(label('i01.month')).fill(month);
      const row = page.getByRole('row', { name: new RegExp(slip.number) });
      await row.getByRole('button', { name: t('en', 'action.i02.amend') }).click();
      await expect(page.getByLabel(label('i02.operation'))).toHaveValue(String(w2p));
      await expect(page.getByLabel(label('i02.bags'))).toHaveValue('345');
      await expect(page.getByLabel(label('i02.stack'))).toHaveValue('8');
      await expect(page.getByLabel(label('i02.start'))).toHaveValue(`${hour}:15`);
    },
  );
});
