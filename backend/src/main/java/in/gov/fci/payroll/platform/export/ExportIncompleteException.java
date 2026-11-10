package in.gov.fci.payroll.platform.export;

/**
 * An export wrote a different number of rows than its query counted in the same snapshot. Thrown
 * while streaming, it aborts the download, so the user gets a failed download, never a short file
 * that looks complete.
 */
public class ExportIncompleteException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public ExportIncompleteException(long expected, long written) {
        super("export incomplete: " + written + " of " + expected + " rows written");
    }
}
