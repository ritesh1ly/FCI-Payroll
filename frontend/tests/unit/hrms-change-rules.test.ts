import { describe, expect, it } from 'vitest';
import {
  MAX_ORDER_TASKS,
  orderable,
  orderComplete,
  pending,
  submittable,
} from '@/modules/employee/business/hrmsChangeRules';

/** I-21 screen rules (plan 16 phase 16a; SRS-1-28, SRS-1-69, SRS-G-31). */
describe('I-21 HRMS change rules', () => {
  it('SRS-1-28: only a task waiting for its order or returned is sent; waiting states hold the lock', () => {
    expect(submittable('PENDING_ORDER_DETAILS')).toBe(true);
    expect(submittable('RETURNED_TO_MAKER')).toBe(true);
    for (const s of ['PENDING_CHECKER', 'APPROVED', 'AUTO_APPLIED', 'REJECTED_RETURNED_TO_HRMS']) {
      expect(submittable(s)).toBe(false);
    }
    for (const s of [
      'PENDING_ORDER_DETAILS',
      'PENDING_CHECKER',
      'PENDING_HQ_ESTABLISHMENT',
      'RETURNED_TO_MAKER',
    ]) {
      expect(pending(s)).toBe(true);
    }
    for (const s of ['APPROVED', 'AUTO_APPLIED', 'REJECTED_RETURNED_TO_HRMS', 'CANCELLED']) {
      expect(pending(s)).toBe(false);
    }
  });

  it('SRS-G-31: an order names number, date and authority, unless it is held in HRMS', () => {
    const full = { orderNo: 'ORD/1', orderDate: '2026-10-20', authority: 'GM (P)', held: false };
    expect(orderComplete(full)).toBe(true);
    expect(orderComplete({ orderNo: '', orderDate: '', authority: '', held: true })).toBe(true);
    expect(orderComplete({ ...full, orderNo: '  ' })).toBe(false);
    expect(orderComplete({ ...full, orderDate: '' })).toBe(false);
    expect(orderComplete({ ...full, authority: ' ' })).toBe(false);
  });

  it('SRS-1-67: one order covers two tasks up to the limit', () => {
    expect(orderable(1)).toBe(false);
    expect(orderable(2)).toBe(true);
    expect(orderable(MAX_ORDER_TASKS)).toBe(true);
    expect(orderable(MAX_ORDER_TASKS + 1)).toBe(false);
    expect(MAX_ORDER_TASKS).toBe(200);
  });
});
