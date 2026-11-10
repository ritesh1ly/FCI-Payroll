import { describe, expect, it } from 'vitest';
import { amountInWords } from '@/modules/eseva/business/esevaRules';

/** The Pay Slip net pay in words (SRS-17-7). */
describe('E-02 amount in words', () => {
  it('SRS-17-7: every number below twenty has its own word', () => {
    expect(Array.from({ length: 19 }, (_, i) => amountInWords(i + 1))).toEqual([
      'One',
      'Two',
      'Three',
      'Four',
      'Five',
      'Six',
      'Seven',
      'Eight',
      'Nine',
      'Ten',
      'Eleven',
      'Twelve',
      'Thirteen',
      'Fourteen',
      'Fifteen',
      'Sixteen',
      'Seventeen',
      'Eighteen',
      'Nineteen',
    ]);
  });

  it('SRS-17-7: every ten from twenty to ninety, alone and with a unit', () => {
    expect([20, 30, 40, 50, 60, 70, 80, 90].map(amountInWords)).toEqual([
      'Twenty',
      'Thirty',
      'Forty',
      'Fifty',
      'Sixty',
      'Seventy',
      'Eighty',
      'Ninety',
    ]);
    expect(amountInWords(78)).toBe('Seventy Eight');
    expect(amountInWords(999)).toBe('Nine Hundred and Ninety Nine');
    expect(amountInWords(0.9)).toBe('Zero and Ninety Paise');
  });
});
