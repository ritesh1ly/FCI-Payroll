import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { I18nProvider, translate } from '@/shared/i18n';
import {
  businessMonth,
  businessYymm,
  finYearOfDay,
  monthToYymm,
  recentFinYears,
  yymmToMonth,
} from '@/shared/time';
import {
  FinancialYearSelect,
  MonthField,
  ReasonDialog,
  ReasonField,
  TextAreaField,
  isPlainText,
} from '@/shared/ui';
import { t } from './support/harness';

/**
 * The shared controls of the UI audit 2026-11-02 (control fixes): the long free-text field (a
 * textarea capped at the column length, with the characters used and the R-ASCII hint, en and hi),
 * the month picker that hands the API its YYYYMM or YYYY-MM and starts on India's business month,
 * and the financial-year selector.
 */

/** The hint and the count under the field (drawn by the stylesheet from these attributes). */
function meta(box: HTMLElement): { hint: string; count: string; invalid: boolean } {
  const el = document.getElementById(box.getAttribute('aria-describedby')?.split(' ').pop() ?? '');
  return {
    hint: el?.getAttribute('data-hint') ?? '',
    count: el?.getAttribute('data-count') ?? '',
    invalid: el?.classList.contains('fl-bad') ?? false,
  };
}

function Controlled({
  max,
  initial = '',
  asciiOnly,
}: {
  max: number;
  initial?: string;
  asciiOnly?: boolean;
}) {
  const [text, setText] = useState(initial);
  return (
    <I18nProvider>
      <label>
        Remark
        <TextAreaField
          maxLength={max}
          value={text}
          asciiOnly={asciiOnly}
          onChange={(e) => setText(e.target.value)}
        />
      </label>
    </I18nProvider>
  );
}

describe('R_ASCII TextAreaField', () => {
  it('is a three-row textarea capped at the column length, with the count and the hint', async () => {
    render(<Controlled max={500} />);
    const box = screen.getByRole('textbox', { name: 'Remark' });
    expect(box.tagName).toBe('TEXTAREA');
    expect(box).toHaveAttribute('maxlength', '500');
    expect(box).toHaveAttribute('rows', '3');
    expect(meta(box)).toEqual({
      hint: t('common.plainTextHint'),
      count: t('common.charCount', [0, 500]),
      invalid: false,
    });
    await userEvent.type(box, 'Line one{Enter}line two');
    expect(box).toHaveValue(['Line one', 'line two'].join(String.fromCharCode(10)));
    expect(meta(box).count).toBe(t('common.charCount', [17, 500]));
    expect(box).not.toHaveAttribute('aria-invalid');
  });

  it('the label stays the field name: the hint is a description, never part of the label text', () => {
    render(<Controlled max={1000} />);
    expect(screen.getByLabelText('Remark')).toBe(screen.getByRole('textbox', { name: 'Remark' }));
    const box = screen.getByRole('textbox');
    const described = box.getAttribute('aria-describedby') ?? '';
    expect(document.getElementById(described)).toHaveAttribute('aria-hidden', 'true');
    expect(document.getElementById(described)).toHaveTextContent('');
    expect(meta(box).count).toBe(t('common.charCount', [0, 1000]));
  });

  it('boundary: the browser stops at maxLength; the count shows the limit reached', () => {
    render(<Controlled max={5} initial="abcde" />);
    expect(screen.getByRole('textbox')).toHaveAttribute('maxlength', '5');
    expect(meta(screen.getByRole('textbox')).count).toBe(t('common.charCount', [5, 5]));
  });

  it('text that is not printable ASCII marks the field invalid and says why', () => {
    render(<Controlled max={500} />);
    const box = screen.getByRole('textbox');
    fireEvent.change(box, { target: { value: 'कारण' } });
    expect(box).toHaveAttribute('aria-invalid', 'true');
    expect(meta(box)).toMatchObject({ hint: t('common.plainTextOnly'), invalid: true });
  });

  it('asciiOnly false (M-26 label text, NVARCHAR2): Hindi is valid and no hint is shown', () => {
    render(<Controlled max={500} asciiOnly={false} />);
    const box = screen.getByRole('textbox');
    fireEvent.change(box, { target: { value: 'वेतन' } });
    expect(box).not.toHaveAttribute('aria-invalid');
    expect(meta(box)).toEqual({ hint: '', count: t('common.charCount', [4, 500]), invalid: false });
  });

  it('uncontrolled (a form read through FormData): counts what is typed and keeps the caller onChange', async () => {
    const onChange = vi.fn();
    render(
      <I18nProvider>
        <form aria-label="f">
          <TextAreaField
            name="reason"
            maxLength={500}
            defaultValue="ab"
            onChange={onChange}
            aria-describedby="x"
          />
        </form>
      </I18nProvider>,
    );
    const box = screen.getByRole('textbox');
    expect(box).toHaveValue('ab');
    await userEvent.type(box, 'c');
    expect(onChange).toHaveBeenCalled();
    expect(meta(box).count).toBe(t('common.charCount', [3, 500]));
    expect(box.getAttribute('aria-describedby')).toMatch(/^x /);
    expect(new FormData(screen.getByRole('form')).get('reason')).toBe('abc');
  });

  it('an empty uncontrolled field starts at zero', () => {
    render(
      <I18nProvider>
        <TextAreaField maxLength={200} rows={2} />
      </I18nProvider>,
    );
    expect(screen.getByRole('textbox')).toHaveAttribute('rows', '2');
    expect(meta(screen.getByRole('textbox')).count).toBe(t('common.charCount', [0, 200]));
  });

  it('isPlainText: space to tilde, tab and line breaks; nothing else', () => {
    expect(isPlainText('')).toBe(true);
    expect(isPlainText(' ~\t\r\n')).toBe(true);
    expect(isPlainText('\u001f')).toBe(false);
    expect(isPlainText('\u007f')).toBe(false);
    expect(isPlainText('é')).toBe(false);
  });

  it('labels and hints exist in English and Hindi', () => {
    for (const key of ['common.plainTextHint', 'common.plainTextOnly', 'common.charCount']) {
      expect(translate('en', key, [1, 2])).not.toBe(key);
      expect(translate('hi', key, [1, 2])).not.toBe(key);
      expect(translate('hi', key, [1, 2])).not.toBe(translate('en', key, [1, 2]));
    }
  });
});

describe('ReasonField and ReasonDialog', () => {
  it('ReasonField: Reason label wrapping the shared field; a label can be given', () => {
    render(
      <I18nProvider>
        <ReasonField maxLength={500} value="" onChange={() => undefined} />
        <ReasonField label="Remark" maxLength={1000} value="" onChange={() => undefined} />
      </I18nProvider>,
    );
    expect(screen.getByRole('textbox', { name: t('common.reason') })).toHaveAttribute('maxlength', '500');
    expect(screen.getByRole('textbox', { name: 'Remark' })).toHaveAttribute('maxlength', '1000');
  });

  it('ReasonDialog: 500 by default, the column length when given (1000), confirm trims', async () => {
    const onConfirm = vi.fn();
    const { rerender } = render(
      <I18nProvider>
        <ReasonDialog title="Why" onConfirm={onConfirm} onCancel={() => undefined} />
      </I18nProvider>,
    );
    expect(screen.getByRole('textbox')).toHaveAttribute('maxlength', '500');
    rerender(
      <I18nProvider>
        <ReasonDialog title="Why" maxLength={1000} onConfirm={onConfirm} onCancel={() => undefined} />
      </I18nProvider>,
    );
    const box = screen.getByRole('textbox');
    expect(box).toHaveAttribute('maxlength', '1000');
    await userEvent.type(box, '  two{Enter}lines  ');
    await userEvent.click(screen.getByRole('button', { name: t('common.submit') }));
    expect(onConfirm).toHaveBeenCalledWith('two\nlines');
  });
});

function Month({ initial, format }: { initial: string; format?: 'YYYYMM' | 'YYYY-MM' }) {
  const [value, setValue] = useState(initial);
  return (
    <>
      <label>
        Month
        <MonthField value={value} format={format} min="202401" max="203012" onChange={setValue} />
      </label>
      <output aria-label="sent">{value}</output>
    </>
  );
}

describe('MonthField', () => {
  it('a month picker showing YYYYMM as YYYY-MM and handing back YYYYMM', () => {
    render(<Month initial="202611" />);
    const box = screen.getByLabelText('Month');
    expect(box).toHaveAttribute('type', 'month');
    expect(box).toHaveValue('2026-11');
    expect(box).toHaveAttribute('min', '2024-01');
    expect(box).toHaveAttribute('max', '2030-12');
    fireEvent.change(box, { target: { value: '2027-01' } });
    expect(screen.getByLabelText('sent')).toHaveTextContent('202701');
  });

  it('cleared, the API gets no month; a bad value is shown as no month', () => {
    render(<Month initial="202613" />);
    const box = screen.getByLabelText('Month');
    expect(box).toHaveValue('');
    fireEvent.change(box, { target: { value: '2026-12' } });
    expect(screen.getByLabelText('sent')).toHaveTextContent('202612');
    fireEvent.change(box, { target: { value: '' } });
    expect(screen.getByLabelText('sent')).toHaveTextContent('');
  });

  it('the period format YYYY-MM passes through unchanged', () => {
    render(<Month initial="2026-04" format="YYYY-MM" />);
    const box = screen.getByLabelText('Month');
    expect(box).toHaveValue('2026-04');
    fireEvent.change(box, { target: { value: '2027-03' } });
    expect(screen.getByLabelText('sent')).toHaveTextContent('2027-03');
  });

  it('a YYYY-MM value that is not a month shows empty; min / max outside a month are left off', () => {
    render(
      <MonthField
        aria-label="p"
        format="YYYY-MM"
        value="2026-13"
        min="x"
        max=""
        onChange={() => undefined}
      />,
    );
    const box = screen.getByLabelText('p');
    expect(box).toHaveValue('');
    expect(box).not.toHaveAttribute('min');
    expect(box).not.toHaveAttribute('max');
  });
});

describe('business month (IST default of the month pickers)', () => {
  it('is India’s month, not the browser’s: 18:30 UTC on the last day is the next month in IST', () => {
    expect(businessMonth(new Date('2026-10-31T18:29:59Z'))).toBe('2026-10');
    expect(businessMonth(new Date('2026-10-31T18:30:00Z'))).toBe('2026-11');
    expect(businessYymm(new Date('2026-12-31T18:30:00Z'))).toBe('202701');
    expect(businessYymm()).toMatch(/^\d{4}(0[1-9]|1[0-2])$/);
  });

  it('converts YYYYMM and YYYY-MM both ways and refuses anything that is not a month', () => {
    expect(yymmToMonth('202604')).toBe('2026-04');
    expect(yymmToMonth(' 202612 ')).toBe('2026-12');
    for (const bad of ['', '2026', '202600', '202613', '2026-04', '20260412'])
      expect(yymmToMonth(bad)).toBe('');
    expect(monthToYymm('2026-04')).toBe('202604');
    for (const bad of ['', '2026-00', '2026-13', '202604', '2026-4']) expect(monthToYymm(bad)).toBe('');
  });
});

describe('FinancialYearSelect', () => {
  it('financial years run April to March: 31 March and 1 April are different years', () => {
    expect(finYearOfDay('2027-03-31')).toBe('2026-27');
    expect(finYearOfDay('2027-04-01')).toBe('2027-28');
    expect(finYearOfDay('2099-12')).toBe('2099-00');
  });

  it('offers the business year and the four before it, newest first (IST)', () => {
    const now = new Date('2027-03-31T18:30:00Z'); // 1 April 2027 in India
    expect(recentFinYears(5, now)).toEqual(['2027-28', '2026-27', '2025-26', '2024-25', '2023-24']);
    expect(recentFinYears(0, now)).toEqual(['2027-28']);
    expect(recentFinYears(1, new Date('2027-03-31T18:29:59Z'))).toEqual(['2026-27']);
  });

  it('a select, never a text box; a year from outside the window stays offered; empty option', () => {
    const onChange = vi.fn();
    const years = recentFinYears();
    const { rerender } = render(
      <FinancialYearSelect aria-label="Year" value={years[1] ?? ''} onChange={onChange} />,
    );
    const select = screen.getByRole('combobox', { name: 'Year' });
    expect(select.tagName).toBe('SELECT');
    expect(screen.getAllByRole('option').map((o) => o.textContent)).toEqual(years);
    fireEvent.change(select, { target: { value: years[2] } });
    expect(onChange).toHaveBeenCalledWith(years[2]);
    rerender(<FinancialYearSelect aria-label="Year" value="2001-02" onChange={onChange} emptyLabel="All" />);
    expect(screen.getAllByRole('option').map((o) => o.textContent)).toEqual(['All', '2001-02', ...years]);
    rerender(<FinancialYearSelect aria-label="Year" value="" count={2} onChange={onChange} />);
    expect(screen.getAllByRole('option')).toHaveLength(2);
  });
});
