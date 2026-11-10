package in.gov.fci.payroll.general.business;

import in.gov.fci.payroll.general.business.model.FallbackEnablement;
import in.gov.fci.payroll.general.business.model.FallbackRecord;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.Csv;
import in.gov.fci.payroll.platform.security.InputPatterns;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Pure rules of the X-07 HRMS fallback upload (SRS-G-46, SRS-EH-11, BR-INT-011; DDL S08 {@code
 * hrms_fallback_upload_line}): read the HRMS export, then validate every record.
 *
 * <p><b>The file format is a setting (decision D-2).</b> SRS v0.5 requires "the HRMS export format"
 * but no source describes it, so the reader takes a {@link Format}: which column header holds each
 * logical field, and the date pattern. A deployment without a configured format refuses uploads; no
 * layout is invented here.
 */
public final class FallbackRules {

    /** Logical fields of the two fallback types; a {@link Format} maps each to a column header. */
    public static final String EMPLOYEE_NUMBER = "employeeNumber";

    public static final String RECORD_TYPE = "recordType";
    public static final String PERIOD_FROM = "periodFrom";
    public static final String PERIOD_TO = "periodTo";
    public static final String PAY_DAYS = "payDays";
    public static final String LWP_DAYS = "lwpDays";
    public static final String HALF_PAY_DAYS = "halfPayDays";
    public static final String OTHER_LEAVE_CODE = "otherLeaveCode";
    public static final String OTHER_LEAVE_DAYS = "otherLeaveDays";
    public static final String EVENT_EFFECTIVE_DATE = "eventEffectiveDate";
    public static final String ORDER_NO = "orderNo";
    public static final String ORDER_DATE = "orderDate";
    public static final String ISSUING_AUTHORITY = "issuingAuthority";

    /** Fields a file of each type must carry (a file without them is not in the export format). */
    private static final Map<FallbackEnablement.Type, List<String>> REQUIRED =
            Map.of(
                    FallbackEnablement.Type.ATTENDANCE_LEAVE,
                    List.of(EMPLOYEE_NUMBER, PERIOD_FROM, PERIOD_TO, PAY_DAYS, LWP_DAYS),
                    FallbackEnablement.Type.SUSPENSION_ABSCONDING,
                    List.of(
                            EMPLOYEE_NUMBER,
                            RECORD_TYPE,
                            EVENT_EFFECTIVE_DATE,
                            ORDER_NO,
                            ORDER_DATE));

    /** Record types of fallback (b) (SRS-G-46: suspension, absconding and revocation orders). */
    public static final Set<String> ORDER_RECORD_TYPES =
            Set.of("SUSPENSION", "ABSCONDING", "REVOCATION");

    /**
     * Upper bound of records in one file (a monthly office file; protects memory and the database).
     */
    public static final int MAX_RECORDS = 50_000;

    /**
     * An HRMS export layout.
     *
     * @param columns logical field -> column header in the file (case-insensitive)
     * @param datePattern e.g. {@code dd-MM-yyyy}
     */
    public record Format(Map<String, String> columns, String datePattern) {
        public boolean isConfigured() {
            return columns != null
                    && !columns.isEmpty()
                    && datePattern != null
                    && !datePattern.isBlank();
        }
    }

    /** A Payroll member as validation needs it. */
    public record Member(Long id, Long locationId) {}

    private FallbackRules() {}

    // ---------------------------------------------------------------- reading

    /**
     * Reads the file into records. The whole file is refused (SRS-G-46: "a file not in the HRMS
     * export format ... is rejected") when the format is not configured, the header lacks a
     * required column, or there is no record; a single unreadable value only rejects its own line
     * ({@code readError}).
     */
    public static List<FallbackRecord> read(
            String text, FallbackEnablement.Type type, Format format) {
        if (format == null || !format.isConfigured()) {
            throw new FciException(GeneralErrorCode.FALLBACK_FORMAT_NOT_CONFIGURED);
        }
        List<List<String>> rows = parseCsv(text);
        if (rows.size() < 2) {
            throw new FciException(GeneralErrorCode.FALLBACK_FORMAT);
        }
        if (rows.size() - 1 > MAX_RECORDS) {
            throw new FciException(GeneralErrorCode.FALLBACK_FORMAT);
        }
        Map<String, Integer> header = new HashMap<>();
        List<String> names = rows.get(0);
        for (int i = 0; i < names.size(); i++) {
            header.put(names.get(i).strip().toUpperCase(Locale.ROOT), i);
        }
        Map<String, Integer> index = new HashMap<>();
        for (Map.Entry<String, String> column : format.columns().entrySet()) {
            Integer at = header.get(column.getValue().strip().toUpperCase(Locale.ROOT));
            if (at != null) {
                index.put(column.getKey(), at);
            }
        }
        for (String required : REQUIRED.get(type)) {
            if (!index.containsKey(required)) {
                throw new FciException(GeneralErrorCode.FALLBACK_FORMAT);
            }
        }
        DateTimeFormatter dates =
                DateTimeFormatter.ofPattern(format.datePattern().replace('y', 'u'), Locale.ROOT)
                        .withResolverStyle(
                                ResolverStyle
                                        .STRICT); // STRICT: 31-02-2026 is an error, not 03-03-2026
        List<FallbackRecord> records = new ArrayList<>();
        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            if (row.stream().allMatch(String::isBlank)) {
                continue; // a trailing empty line is not a record
            }
            records.add(record(r, row, index, dates, type, ascii(String.join(",", row))));
        }
        if (records.isEmpty()) {
            throw new FciException(GeneralErrorCode.FALLBACK_FORMAT);
        }
        return records;
    }

    private static FallbackRecord record(
            int lineNo,
            List<String> row,
            Map<String, Integer> index,
            DateTimeFormatter dates,
            FallbackEnablement.Type type,
            String raw) {
        Reader in = new Reader(row, index, dates);
        // Fallback (a) files carry attendance only: the record type is implied when there is no
        // column.
        String recordType =
                type == FallbackEnablement.Type.ATTENDANCE_LEAVE && !index.containsKey(RECORD_TYPE)
                        ? "ATTENDANCE_LEAVE"
                        : upper(in.text(RECORD_TYPE));
        return new FallbackRecord(
                lineNo,
                in.text(EMPLOYEE_NUMBER),
                recordType,
                in.date(PERIOD_FROM),
                in.date(PERIOD_TO),
                in.number(PAY_DAYS),
                in.number(LWP_DAYS),
                in.number(HALF_PAY_DAYS),
                in.text(OTHER_LEAVE_CODE),
                in.number(OTHER_LEAVE_DAYS),
                in.date(EVENT_EFFECTIVE_DATE),
                in.text(ORDER_NO),
                in.date(ORDER_DATE),
                in.text(ISSUING_AUTHORITY),
                raw,
                in.error);
    }

    /** Reads typed values of one row and remembers the first field it could not read. */
    private static final class Reader {
        private final List<String> row;
        private final Map<String, Integer> index;
        private final DateTimeFormatter dates;
        private String error;

        Reader(List<String> row, Map<String, Integer> index, DateTimeFormatter dates) {
            this.row = row;
            this.index = index;
            this.dates = dates;
        }

        String text(String field) {
            Integer at = index.get(field);
            if (at == null || at >= row.size()) {
                return null;
            }
            String value = row.get(at).strip();
            if (value.isEmpty()) {
                return null;
            }
            // R-ASCII (US7ASCII database): a cell holding any character outside printable ASCII
            // (a byte above 127 decodes to U+FFFD) is not read, so its record is rejected with
            // its reason instead of storing a value the database cannot hold.
            if (!InputPatterns.isPrintableAscii(value)) {
                fail(field);
                return null;
            }
            return value;
        }

        LocalDate date(String field) {
            String value = text(field);
            try {
                return value == null ? null : LocalDate.parse(value, dates);
            } catch (DateTimeParseException ex) {
                fail(field);
                return null;
            }
        }

        BigDecimal number(String field) {
            String value = text(field);
            try {
                return value == null ? null : new BigDecimal(value);
            } catch (NumberFormatException ex) {
                fail(field);
                return null;
            }
        }

        private void fail(String field) {
            if (error == null) {
                error = field;
            }
        }
    }

    /** RFC 4180 CSV (see {@link Csv#parse}). */
    public static List<List<String>> parseCsv(String text) {
        return Csv.parse(text);
    }

    // ---------------------------------------------------------------- validation

    /**
     * Validates every record (SRS X-07: "employee on the Payroll master, office enabled, period, no
     * overlapping leave dates, no duplicate"; SRS-G-46: no other item than the fallback type).
     *
     * @param members Payroll member by employee number (EIS)
     * @param enabledLocations the uploading office and the units under it
     * @return one entry per record: null when accepted, else the rejection reason ("CODE: text")
     */
    public static List<String> validate(
            List<FallbackRecord> records,
            FallbackEnablement.Type type,
            YearMonth month,
            Function<String, Optional<Member>> members,
            Set<Long> enabledLocations) {
        List<String> reasons = new ArrayList<>(records.size());
        Map<String, List<LocalDate[]>> periods = new HashMap<>();
        Set<String> seen = new HashSet<>();
        for (FallbackRecord r : records) {
            String reason = reason(r, type, month, members, enabledLocations);
            if (reason == null) {
                String identity =
                        r.employeeNumber()
                                + "|"
                                + r.recordType()
                                + "|"
                                + r.periodFrom()
                                + "|"
                                + r.periodTo()
                                + "|"
                                + r.eventEffectiveDate();
                if (!seen.add(identity)) {
                    reason = "DUPLICATE: the same record appears earlier in the file";
                } else if (type == FallbackEnablement.Type.ATTENDANCE_LEAVE
                        && overlaps(periods, r)) {
                    reason = "OVERLAP: leave dates overlap another record of the same employee";
                }
            }
            reasons.add(reason);
        }
        return reasons;
    }

    private static String reason(
            FallbackRecord r,
            FallbackEnablement.Type type,
            YearMonth month,
            Function<String, Optional<Member>> members,
            Set<Long> enabledLocations) {
        if (r.readError() != null) {
            return "UNREADABLE: the value of " + r.readError() + " could not be read";
        }
        if (r.employeeNumber() == null) {
            return "EMPLOYEE_NOT_FOUND: no employee number";
        }
        // SRS-G-46: "a file ... holding any item other than the two fallback types is rejected".
        // A line without a record type is such an item (SRS-EH-11: rejected with its reason); the
        // null check comes first because Set.of(...).contains(null) throws.
        boolean typeFits =
                type == FallbackEnablement.Type.ATTENDANCE_LEAVE
                        ? "ATTENDANCE_LEAVE".equals(r.recordType())
                        : r.recordType() != null && ORDER_RECORD_TYPES.contains(r.recordType());
        if (!typeFits) {
            return "WRONG_TYPE: not a " + type.name() + " record";
        }
        Optional<Member> member = members.apply(r.employeeNumber());
        if (member.isEmpty()) {
            return "EMPLOYEE_NOT_FOUND: not on the Payroll master";
        }
        if (!enabledLocations.contains(member.get().locationId())) {
            return "OFFICE_NOT_ENABLED: the employee's office is not enabled for this upload";
        }
        return type == FallbackEnablement.Type.ATTENDANCE_LEAVE
                ? attendance(r, month)
                : order(r, month);
    }

    /**
     * Fallback (a): a period inside the payroll month, and day counts between 0 and the days in it.
     */
    private static String attendance(FallbackRecord r, YearMonth month) {
        if (r.periodFrom() == null
                || r.periodTo() == null
                || r.periodTo().isBefore(r.periodFrom())
                || !YearMonth.from(r.periodFrom()).equals(month)
                || !YearMonth.from(r.periodTo()).equals(month)) {
            return "PERIOD: the period must lie within " + month;
        }
        BigDecimal days =
                BigDecimal.valueOf(r.periodTo().toEpochDay() - r.periodFrom().toEpochDay() + 1);
        for (BigDecimal value :
                new BigDecimal[] {r.payDays(), r.lwpDays(), r.halfPayDays(), r.otherLeaveDays()}) {
            if (value != null && (value.signum() < 0 || value.compareTo(days) > 0)) {
                return "DAYS: a day count is negative or exceeds the days in the period";
            }
        }
        if (r.payDays() == null || r.lwpDays() == null) {
            return "DAYS: pay days and leave without pay are required";
        }
        return null;
    }

    /**
     * Fallback (b): the order particulars ({@code chk_hrms_fb_line_order}) and an effective date
     * not after the payroll month (a later order belongs to a later cycle).
     */
    private static String order(FallbackRecord r, YearMonth month) {
        if (r.orderNo() == null || r.orderDate() == null || r.eventEffectiveDate() == null) {
            return "ORDER: order number, order date and effective date are required";
        }
        if (r.eventEffectiveDate().isAfter(month.atEndOfMonth())) {
            return "PERIOD: the effective date is after " + month;
        }
        return null;
    }

    private static boolean overlaps(Map<String, List<LocalDate[]>> periods, FallbackRecord r) {
        List<LocalDate[]> taken =
                periods.computeIfAbsent(r.employeeNumber(), k -> new ArrayList<>());
        for (LocalDate[] p : taken) {
            if (!r.periodFrom().isAfter(p[1]) && !r.periodTo().isBefore(p[0])) {
                return true;
            }
        }
        taken.add(new LocalDate[] {r.periodFrom(), r.periodTo()});
        return false;
    }

    /**
     * R-ASCII: the record's source line as kept beside it, each character outside printable ASCII
     * shown as {@code ?} (the line is evidence of what was received, never a value used).
     */
    static String ascii(String line) {
        StringBuilder out = new StringBuilder(line.length());
        line.chars().forEach(c -> out.append(c >= 0x20 && c <= 0x7E ? (char) c : '?'));
        return out.toString();
    }

    private static String upper(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }
}
