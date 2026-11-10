package in.gov.fci.payroll.platform.export;

/**
 * A document could not be rendered (OD-144): nothing was sent; the audit records the attempt
 * (SRS-20-24: every export is logged) with the document name and the error class, never the
 * document's content or personal data.
 *
 * @param name the document's base name
 * @param format the format being written
 * @param error the simple class name of the failure
 */
public record ExportFailed(String name, ExportFormat format, String error) {}
