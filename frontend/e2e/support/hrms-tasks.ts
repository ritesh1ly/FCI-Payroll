import { expect, type Page } from '@playwright/test';
import { onSomePage, t } from './app';
import { sql, sqlRows } from './db';
import { PDF } from './payroll-support';

/**
 * A pending HRMS change of an active Delhi member, as the interface would record it (the same row
 * 01-hrms-change-approval writes); {@code offset} picks the member. Returns its task number.
 */
export function receivedTask(field: string, value: string, offset: number): string {
  const stamp = `${offset}${`${Date.now()}`.slice(-9)}`;
  const taskNo = `HCA-E2E-${field.slice(0, 3)}${stamp}`;
  sql(
    `INSERT INTO hrms_change_task (task_no, employee_id, pay_effect_field_id, task_source, hrms_request_id, new_value, effective_date, received_at, paying_unit_id, target_payroll_yymm, received_before_freeze, task_status, otp_required) SELECT '${taskNo}', e.employee_id, f.pay_effect_field_id, 'HRMS_PUSH', 'E2E-${stamp}-${field}', '${value}', CURRENT_DATE, CURRENT_TIMESTAMP, l.location_id, TO_CHAR(ADD_MONTHS(CURRENT_DATE, 1), 'YYYYMM'), 'N', 'PENDING_ORDER_DETAILS', f.otp_required FROM org_location l JOIN hrms_pay_effect_field f ON f.field_code = '${field}' JOIN (SELECT employee_id, location_id, ROW_NUMBER() OVER (PARTITION BY location_id ORDER BY emp_num) - 1 AS n FROM employee WHERE record_status = 'ACTIVE') e ON e.location_id = l.location_id AND e.n = ${offset} WHERE l.location_code = 'DH001'`,
  );
  // An offset past the unit's members inserts nothing and says nothing.
  expect(sqlRows(`SELECT task_no FROM hrms_change_task WHERE task_no = '${taskNo}'`)).toHaveLength(1);
  return taskNo;
}

/** The Maker's order on I-21 (SRS-G-31): the task goes to the Checker. */
export async function sendTaskOrder(page: Page, taskNo: string) {
  const view = page.getByRole('button', { name: t('en', 'i21.w.viewOf', [taskNo]) });
  await (await onSomePage(page, view)).click();
  const form = page.getByRole('form', { name: t('en', 'i21.order') });
  await form.getByLabel(t('en', 'i21.orderNo')).fill('EST/2026/E2E');
  await form.getByLabel(t('en', 'i21.orderDate')).fill('2026-10-01');
  await form.getByLabel(t('en', 'i21.authority')).fill('General Manager (P), Delhi');
  await form.getByLabel(t('en', 'ord.type')).selectOption({ index: 1 });
  await form.getByLabel(t('en', 'i21.copy')).setInputFiles(PDF);
  await form.getByRole('button', { name: t('en', 'i21.submit') }).click();
  await expect(page.getByRole('status').filter({ hasText: taskNo })).toContainText(
    t('en', 'i21.submitted', [taskNo]),
  );
}
