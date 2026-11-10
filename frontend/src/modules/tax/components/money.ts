/** An amount in rupees and paise, Indian grouping; a dash when there is none. */
export function rupees(n: number | null | undefined): string {
  return n === null || n === undefined
    ? '—'
    : `₹${Number(n).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}
