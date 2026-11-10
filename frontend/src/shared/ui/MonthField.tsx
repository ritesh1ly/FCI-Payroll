'use client';

import type { InputHTMLAttributes, SelectHTMLAttributes } from 'react';
import { monthToYymm, recentFinYears, yymmToMonth } from '@/shared/time';

/** The format an API takes a month in: the payroll month YYYYMM, or the period YYYY-MM. */
export type MonthFormat = 'YYYYMM' | 'YYYY-MM';

function toPicker(value: string, format: MonthFormat): string {
  return format === 'YYYYMM' ? yymmToMonth(value) : /^\d{4}-(0[1-9]|1[0-2])$/.test(value) ? value : '';
}

function fromPicker(value: string, format: MonthFormat): string {
  return format === 'YYYYMM' ? monthToYymm(value) : value;
}

export type MonthFieldProps = Omit<
  InputHTMLAttributes<HTMLInputElement>,
  'type' | 'value' | 'defaultValue' | 'onChange' | 'min' | 'max'
> & {
  /** The month in the API's format ('' for none). */
  value: string;
  /** The month chosen, in the API's format ('' when the picker is cleared). */
  onChange: (value: string) => void;
  /** The API's format; YYYYMM (payroll_result.yymm and the yymm parameters) unless said. */
  format?: MonthFormat;
  /** The earliest and latest month offered, in the API's format. */
  min?: string;
  max?: string;
};

/**
 * The month and period control of every screen: a month picker (`<input type="month">`, shown as
 * the browser's month and year), whose value is turned into the format the API expects. The
 * caller sets the starting month (normally the business month in India, businessMonth()); the
 * server still validates every month it receives.
 */
export function MonthField({ value, onChange, format = 'YYYYMM', min, max, ...rest }: MonthFieldProps) {
  return (
    <input
      {...rest}
      type="month"
      value={toPicker(value, format)}
      min={min ? toPicker(min, format) || undefined : undefined}
      max={max ? toPicker(max, format) || undefined : undefined}
      onChange={(e) => onChange(fromPicker(e.target.value, format))}
    />
  );
}

export type FinancialYearSelectProps = Omit<
  SelectHTMLAttributes<HTMLSelectElement>,
  'value' | 'defaultValue' | 'onChange'
> & {
  value: string;
  onChange: (finYear: string) => void;
  /** How many years, the business year first (default 5: this year and the four before it). */
  count?: number;
  /** A first option for "no year" (a filter that may be left open), with its label. */
  emptyLabel?: string;
};

/**
 * A financial-year selector (2026-27): the business year in India and the ones before it, so a
 * year is chosen, never typed (a typed "2026-99" was accepted by the text box). A value from
 * elsewhere (a link, a saved filter) that is not in the window stays offered, so it is never
 * changed silently. The server still checks the year (for example C-10: a fully locked year).
 */
export function FinancialYearSelect({
  value,
  onChange,
  count = 5,
  emptyLabel,
  ...rest
}: FinancialYearSelectProps) {
  const years = recentFinYears(count);
  const options = value !== '' && !years.includes(value) ? [value, ...years] : years;
  return (
    <select {...rest} value={value} onChange={(e) => onChange(e.target.value)}>
      {emptyLabel !== undefined && <option value="">{emptyLabel}</option>}
      {options.map((y) => (
        <option key={y} value={y}>
          {y}
        </option>
      ))}
    </select>
  );
}
