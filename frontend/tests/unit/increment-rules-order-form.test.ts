import { describe, expect, it } from 'vitest';
import { orderForm } from '@/modules/increment/business/incrementRules';

/** The drawn order card's upload (SRS-G-30): one multipart form per file. */
describe('I-05 order card upload forms', () => {
  const file = (name: string) => new File(['x'], name, { type: 'application/pdf' });
  const entries = (f: FormData) =>
    [...f.entries()].map(([k, v]) => [k, typeof v === 'string' ? v : (v as File).name]);

  it('SRS-G-30: each file goes in its own form with the order particulars, trimmed', () => {
    const forms = orderForm({
      documentType: 'INCREMENT_ORDER',
      reference: '  ORD/7  ',
      orderDate: '2026-10-01',
      issuingOffice: '  ZO North  ',
      files: [file('a.pdf'), file('b.pdf')],
    });
    expect(forms.map(entries)).toEqual([
      [
        ['documentType', 'INCREMENT_ORDER'],
        ['reference', 'ORD/7'],
        ['orderDate', '2026-10-01'],
        ['issuingAuthority', 'ZO North'],
        ['file', 'a.pdf'],
      ],
      [
        ['documentType', 'INCREMENT_ORDER'],
        ['reference', 'ORD/7'],
        ['orderDate', '2026-10-01'],
        ['issuingAuthority', 'ZO North'],
        ['file', 'b.pdf'],
      ],
    ]);
  });

  it('SRS-G-30: a blank reference is left out; no file, no form', () => {
    const form = orderForm({
      documentType: 'INCREMENT_ORDER',
      reference: '   ',
      orderDate: '2026-10-01',
      issuingOffice: 'HQ',
      files: [file('a.pdf')],
    })[0];
    expect([...(form?.keys() ?? [])]).toEqual(['documentType', 'orderDate', 'issuingAuthority', 'file']);
    expect(
      orderForm({
        documentType: 'X',
        reference: 'R',
        orderDate: '2026-10-01',
        issuingOffice: 'HQ',
        files: [],
      }),
    ).toEqual([]);
  });
});
