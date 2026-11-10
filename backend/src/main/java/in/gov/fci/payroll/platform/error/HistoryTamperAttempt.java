package in.gov.fci.payroll.platform.error;

/**
 * The database refused a change to the audit trail or the Legacy Audit table (FCIPAY-AUD-001 /
 * FCIPAY-AUD-002, triggers {@code trg_audit_event_append_only} / {@code
 * trg_legacy_audit_read_only}). SRS-20-19 and SRS-20-21 require the attempt to be logged as a
 * security exception: the audit module records it as a security event.
 *
 * @param code FCIPAY-AUD-001 (audit trail) or FCIPAY-AUD-002 (Legacy Audit)
 * @param method the HTTP method of the request that caused it
 * @param path the request path
 */
public record HistoryTamperAttempt(String code, String method, String path) {

    /** FCIPAY-AUD-001 or FCIPAY-AUD-002 in the cause chain of {@code failure}, or null. */
    public static String codeIn(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            String message = t.getMessage();
            if (message != null) {
                if (message.contains("FCIPAY-AUD-001")) {
                    return "FCIPAY-AUD-001";
                }
                if (message.contains("FCIPAY-AUD-002")) {
                    return "FCIPAY-AUD-002";
                }
            }
        }
        return null;
    }
}
