package in.gov.fci.payroll.tax.business;

import in.gov.fci.payroll.platform.error.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * Errors of the income-tax module (Module 08; docs/api/errors.md). The screen shows {@code
 * error.<code>} from its bundle (English and Hindi); the texts here are English defaults.
 */
public enum TaxErrorCode implements ErrorCode {
    // ---------------------------------------------------------------- M-18 section master (08b)
    /** SRS-7-20: a section is named by its code, e.g. 80C, 80CCD(1B), 24(b). */
    SECTION_CODE(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "TAX-422-SECTION-CODE",
            "tax/section-code",
            "Section code not valid",
            "Enter the section as in the Income-tax Act: letters, digits and brackets, up to 20"
                    + " characters (e.g. 80C, 80CCD(1B), 24(b))."),
    /** BR-TAX-002: the limit is an amount of rupees and paise, never negative. */
    SECTION_LIMIT(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "TAX-422-SECTION-LIMIT",
            "tax/section-limit",
            "Section limit not valid",
            "A section limit is an amount from 0 with at most two decimals and ten digits before"
                    + " the point, or empty when the section has no fixed limit."),
    /** DDL it_section_master.regime_applicability. */
    SECTION_REGIME(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "TAX-422-SECTION-REGIME",
            "tax/section-regime",
            "Regime not valid",
            "Say whether the section applies under the old regime, the new regime or both."),
    /** Effective-dated master: a new version starts after the version it replaces. */
    SECTION_ORDER(
            HttpStatus.CONFLICT,
            "TAX-409-SECTION-ORDER",
            "tax/section-order",
            "Earlier than the version in force",
            "A new version of section {0} starts after the version it replaces ({1})."),

    // ---------------------------------------------------------------- M-18 PT states (08b)
    /** SRS-7-4, BR-TAX-006: a state is named by its code. */
    PT_STATE(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "TAX-422-PT-STATE",
            "tax/pt-state",
            "State not valid",
            "Enter the state code (2 to 10 capital letters), its name and the dates the entry"
                    + " applies; the end is not before the start."),

    // ---------------------------------------------------------------- I-07 / E-01 declarations
    // (08c)
    /** I-07 precondition: the financial year's declaration window is open. */
    DECLARATION_WINDOW(
            HttpStatus.CONFLICT,
            "TAX-409-DECLARATION-WINDOW",
            "tax/declaration-window",
            "Declarations not open",
            "No declaration window is open today (M-18 TAX.DECLARATION.WINDOW): declarations"
                    + " cannot be submitted."),
    /** SRS-7-1: a regime choice is required. */
    REGIME_REQUIRED(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "TAX-422-REGIME-REQUIRED",
            "tax/regime-required",
            "Regime required",
            "Choose the old or the new tax regime for the financial year."),
    /** SRS-7-1, BR-TAX-001: the regime is frozen for the year. */
    REGIME_FROZEN(
            HttpStatus.CONFLICT,
            "TAX-409-REGIME-FROZEN",
            "tax/regime-frozen",
            "Regime frozen",
            "The regime for {0} is frozen; it changes only through an authorised reset."),
    /** SRS-7-15: the rent declaration is frozen for the year. */
    RENT_FROZEN(
            HttpStatus.CONFLICT,
            "TAX-409-RENT-FROZEN",
            "tax/rent-frozen",
            "Rent declaration frozen",
            "The rent declaration for {0} is frozen for the year; it is carried forward as"
                    + " approved."),
    /** SRS-7-16: a declared amount in rupees and paise above zero. */
    DECLARATION_AMOUNT(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "TAX-422-DECLARATION-AMOUNT",
            "tax/declaration-amount",
            "Amount not valid",
            "A declared amount is above zero, with at most two decimals and twelve digits"
                    + " before the point."),
    /** SRS-7-20: the section is in the section master and applies under the regime. */
    DECLARATION_SECTION(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "TAX-422-DECLARATION-SECTION",
            "tax/declaration-section",
            "Section not available",
            "Section {0} is not in the section master today or does not apply under the {1}"
                    + " regime."),
    /** One line per section and kind in a round (DDL uk_it_declaration). */
    DECLARATION_DUPLICATE(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "TAX-422-DECLARATION-DUPLICATE",
            "tax/declaration-duplicate",
            "Section declared twice",
            "Section {0} is declared twice; declare it once with the whole amount."),
    /** BR-TAX-014: a round at the Checker is not replaced. */
    DECLARATION_PENDING(
            HttpStatus.CONFLICT,
            "TAX-409-DECLARATION-PENDING",
            "tax/declaration-pending",
            "Declaration at the Checker",
            "Your declaration is awaiting the Payroll Checker; submit again after the decision."),
    /** The round is not at the step the action belongs to. */
    DECLARATION_STATE(
            HttpStatus.CONFLICT,
            "TAX-409-DECLARATION-STATE",
            "tax/declaration-state",
            "Not at this step",
            "The declaration is {0}; this action is not available at that step."),
    /** SRS-7-24: the verified amount is at most the allowed amount. */
    VERIFIED_AMOUNT(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "TAX-422-VERIFIED-AMOUNT",
            "tax/verified-amount",
            "Verified amount not valid",
            "Each line is verified at an amount from 0 up to its allowed amount, once."),
    // ---------------------------------------------------------------- I-18 savings and income
    // (08d)
    /** SRS-7-20, SRS-7-11: external savings name a section; other income names its head. */
    ENTRY_KIND(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "TAX-422-ENTRY-KIND",
            "tax/entry-kind",
            "Section or income head",
            "External savings name a section of the section master in force; other income names"
                    + " its income head (up to 60 characters) and no section."),
    /** SRS-7-25: an amount above zero; TDS from other sources from zero. */
    ENTRY_AMOUNT(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "TAX-422-ENTRY-AMOUNT",
            "tax/entry-amount",
            "Amount not valid",
            "The amount is above zero and the TDS from other sources from zero, each with at most"
                    + " two decimals and twelve digits before the point."),
    /** Q-08-5: entries are for the current financial year. */
    ENTRY_YEAR(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "TAX-422-ENTRY-YEAR",
            "tax/entry-year",
            "Financial year not open",
            "Entries are recorded for the current financial year ({0})."),
    /** Transport rebate: one row per month of the year, above zero. */
    REBATE_MONTHS(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "TAX-422-REBATE-MONTHS",
            "tax/rebate-months",
            "Rebate months not valid",
            "Give at least one month of {0}, each once and not already approved, with an amount"
                    + " above zero."),

    // ---------------------------------------------------------------- tax computation (08e)
    /** R-RULE, SRS-7-26: a rate of the year without an approved version. */
    RATE_NOT_CONFIGURED(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "TAX-422-RATE-NOT-CONFIGURED",
            "tax/rate-not-configured",
            "Rate not configured",
            "{0} has no approved version for the year (M-18): tax is not computed on an assumed"
                    + " rate."),
    /** Plan 08 D-4: the taxable earnings come from the payroll engines (module 16). */
    EARNINGS_NOT_AVAILABLE(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "TAX-422-EARNINGS-NOT-AVAILABLE",
            "tax/earnings-not-available",
            "Earnings not available",
            "The taxable earnings of the year are not available from payroll yet: the tax is not"
                    + " computed on assumed earnings."),
    /**
     * SRS-7-30 (owner 2026-11-09): the salary TDS certificate of tax year 2026-27 onward is the
     * form of the Income-tax Act 2025 (reported as Form 130), whose format is not notified in the
     * sources: nothing is printed on a guessed layout (PENDING LIB-1-F130).
     */
    CERTIFICATE_FORMAT_NOT_NOTIFIED(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "TAX-422-CERTIFICATE-FORMAT",
            "tax/certificate-format",
            "Certificate format not notified",
            "The salary TDS certificate of {0} follows the Income-tax Act 2025, whose prescribed"
                    + " format is not yet configured: the certificate is not printed on an assumed"
                    + " layout."),
    /**
     * OD-153 (owner 2026-11-09, "Disable PDF with reason"): a certificate released before issued
     * copies were kept has no PDF on file; it is never recomputed, since a statutory document
     * already issued is printed only as issued.
     */
    NO_ISSUED_COPY(
            HttpStatus.CONFLICT,
            "TAX-409-NO-ISSUED-COPY",
            "tax/no-issued-copy",
            "No issued copy on file",
            "This certificate was released before issued copies were kept: no PDF is on file, and"
                    + " an issued certificate is never printed again from recomputed figures."),
    /**
     * R-PII, SRS-7-9: the uploaded Part A names PANs and none is the employee's: it belongs to
     * someone else and is not merged or sent.
     */
    PART_A_MISMATCH(
            HttpStatus.CONFLICT,
            "TAX-409-PART-A-PAN",
            "tax/part-a-pan",
            "Part A of another employee",
            "The Part A file names a PAN that is not this employee's: upload the employee's own"
                    + " Part A from TRACES."),
    /** SRS-7-28: a what-if change the forecast cannot take. */
    WHAT_IF(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "TAX-422-WHAT-IF",
            "tax/what-if",
            "What-if not valid",
            "The what-if change is not valid: {0}."),

    // ---------------------------------------------------------------- M-17 output definitions
    // (08f)
    /** SRS M-17: effective dates of versions never overlap. */
    OUTPUT_ORDER(
            HttpStatus.CONFLICT,
            "TAX-409-OUTPUT-ORDER",
            "tax/output-order",
            "Earlier than the version in force",
            "A new version of {0} starts after the version it replaces ({1})."),
    /** BR-TAX-013: every field binds to the component catalogue or the field-source model. */
    OUTPUT_BINDING(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "TAX-422-OUTPUT-BINDING",
            "tax/output-binding",
            "Field does not bind",
            "Field \"{0}\" does not bind: {1}."),
    /**
     * SRS M-17: an output without an Active definition is blocked, never generated on an assumed
     * layout.
     */
    OUTPUT_BLOCKED(
            HttpStatus.CONFLICT,
            "TAX-409-OUTPUT-BLOCKED",
            "tax/output-blocked",
            "Output blocked",
            "{0} has no Active definition (M-17) for {1}: it is not generated on an assumed"
                    + " layout."),

    // ---------------------------------------------------------------- C-10 / C-11 (08g, 08h)
    /** SRS-7-8: no certificate or return for an unlocked period. */
    PERIOD_NOT_LOCKED(
            HttpStatus.CONFLICT,
            "TAX-409-PERIOD-NOT-LOCKED",
            "tax/period-not-locked",
            "Period not locked",
            "{0} of the paying unit is not locked: no certificate or return is generated for an"
                    + " unlocked period."),
    /**
     * SRS-7-8, BR-TAX-012: reconciled to the posted payroll and remitted challans before release.
     */
    NOT_RECONCILED(
            HttpStatus.CONFLICT,
            "TAX-409-NOT-RECONCILED",
            "tax/not-reconciled",
            "Not reconciled",
            "Release is blocked until the figures reconcile: {0}."),
    /** SRS-7-21 import: one line of the challan file. */
    CHALLAN_FILE(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "TAX-422-CHALLAN-FILE",
            "tax/challan-file",
            "Challan file not valid",
            "Line {0} of the challan file: {1}."),

    /** Maker-checker: nobody verifies a declaration of their own. */
    OWN_DECLARATION(
            HttpStatus.FORBIDDEN,
            "TAX-403-OWN-DECLARATION",
            "tax/own-declaration",
            "Own declaration",
            "You cannot verify, return or reset your own declaration.");

    private final HttpStatus status;
    private final String code;
    private final String type;
    private final String defaultTitle;
    private final String defaultDetail;

    TaxErrorCode(
            HttpStatus status,
            String code,
            String type,
            String defaultTitle,
            String defaultDetail) {
        this.status = status;
        this.code = code;
        this.type = type;
        this.defaultTitle = defaultTitle;
        this.defaultDetail = defaultDetail;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String type() {
        return type;
    }

    @Override
    public String defaultTitle() {
        return defaultTitle;
    }

    @Override
    public String defaultDetail() {
        return defaultDetail;
    }
}
