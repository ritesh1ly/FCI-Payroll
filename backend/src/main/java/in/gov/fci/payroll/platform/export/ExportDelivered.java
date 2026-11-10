package in.gov.fci.payroll.platform.export;

/**
 * An export was written in full (SRS-20-24: every export is logged with the requester, the scope
 * and the time). Published on the export thread before the file is closed, so a download whose log
 * cannot be written is aborted, never delivered unlogged; the audit module records it.
 *
 * @param name the file's base name (the list exported)
 * @param format CSV or XLSX
 * @param rows the rows written
 * @param loginId the requester, or null outside a session
 * @param locationId the office of the requester's session
 * @param scope the access groups the requester was acting in (codes, comma-separated)
 * @param parameters the request's filters ({@code name=value}, sorted by name, joined with {@code
 *     &}; SRS-22-29: every report download is logged with its parameters), or null outside a
 *     request
 */
public record ExportDelivered(
        String name,
        ExportFormat format,
        long rows,
        String loginId,
        Long locationId,
        String scope,
        String parameters) {}
