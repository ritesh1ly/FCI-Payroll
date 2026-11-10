package in.gov.fci.payroll.tax.batch;

/** Job execution-context keys of the Module 08 background jobs (set once, kept on restart). */
final class TaxJobKeys {

    static final String UNIT_ID = "fci.tax.unitId";
    static final String UNIT_CODE = "fci.tax.unitCode";
    static final String ACTOR = "fci.tax.actor";
    static final String YEAR_END = "fci.tax.yearEnd";
    static final String VERSION_ID = "fci.tax.versionId";
    static final String VERSION_NO = "fci.tax.versionNo";
    static final String RETURN_ID = "fci.tax.returnId";
    static final String MONTHS = "fci.tax.months";
    static final String QUARTER_END = "fci.tax.quarterEnd";
    static final String CHALLAN_ID = "fci.tax.challanId";

    private TaxJobKeys() {}
}
