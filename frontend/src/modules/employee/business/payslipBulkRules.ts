/**
 * OD-149: why the E-01 bulk download cannot act for the months chosen, as a label key with its argument, or null
 * when it can. No month chosen: none. One month needs no setting (a single PDF). Several months need the setting
 * and at most its number (the server refuses the same, with both numbers).
 */
export function bulkWhy(
  selected: number,
  maxPeriods: number | null | undefined,
): { key: string; args: unknown[] } | null {
  if (selected === 0) return { key: 'e01p.bulk.why.none', args: [] };
  if (selected === 1) return null;
  if (maxPeriods === null || maxPeriods === undefined || maxPeriods < 1) {
    return { key: 'e01p.bulk.why.notConfigured', args: [] };
  }
  return selected > maxPeriods ? { key: 'e01p.bulk.why.tooMany', args: [maxPeriods] } : null;
}

/** The download's file name when the server gives none: a PDF for one month, a ZIP for several. */
export function bulkFileName(months: readonly string[]): string {
  const chosen = [...new Set(months)].sort();
  return chosen.length === 1 ? `payslip-${chosen[0]}.pdf` : `payslips-${chosen[0]}-${chosen.at(-1)}.zip`;
}

/**
 * OD-149 (owner 2026-11-09: "bulk download will be zipped, single month PDF"): one month is its PDF, several
 * months one ZIP of single-month PDFs; the months are sent oldest first, without duplicates.
 */
export function payslipDownloadPath(months: readonly string[]): string {
  const chosen = [...new Set(months)].sort();
  return chosen.length === 1
    ? `/v1/payslips/mine/${chosen[0]}/pdf`
    : `/v1/payslips/mine/zip?months=${chosen.join(',')}`;
}
