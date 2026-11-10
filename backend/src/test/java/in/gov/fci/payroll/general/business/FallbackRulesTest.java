package in.gov.fci.payroll.general.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.general.business.model.FallbackEnablement;
import in.gov.fci.payroll.general.business.model.FallbackRecord;
import in.gov.fci.payroll.platform.error.FciException;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * X-07 reading and validation (SRS-G-46, SRS-EH-11, BR-INT-011). The layout here is a test fixture,
 * like the placeholder of the demo profile: the real HRMS export layout is not specified (D-2).
 */
class FallbackRulesTest {

    private static final YearMonth FEB = YearMonth.of(2026, 2);
    private static final FallbackEnablement.Type A = FallbackEnablement.Type.ATTENDANCE_LEAVE;
    private static final FallbackEnablement.Type B = FallbackEnablement.Type.SUSPENSION_ABSCONDING;

    private static final FallbackRules.Format FORMAT_A =
            new FallbackRules.Format(
                    Map.of(
                            FallbackRules.EMPLOYEE_NUMBER,
                            "EMP_NO",
                            FallbackRules.PERIOD_FROM,
                            "FROM_DATE",
                            FallbackRules.PERIOD_TO,
                            "TO_DATE",
                            FallbackRules.PAY_DAYS,
                            "PAY_DAYS",
                            FallbackRules.LWP_DAYS,
                            "LWP_DAYS",
                            FallbackRules.HALF_PAY_DAYS,
                            "HALF_PAY_DAYS"),
                    "dd-MM-yyyy");
    private static final FallbackRules.Format FORMAT_B =
            new FallbackRules.Format(
                    Map.of(
                            FallbackRules.EMPLOYEE_NUMBER,
                            "EMP_NO",
                            FallbackRules.RECORD_TYPE,
                            "EVENT",
                            FallbackRules.EVENT_EFFECTIVE_DATE,
                            "EFFECTIVE_DATE",
                            FallbackRules.ORDER_NO,
                            "ORDER_NO",
                            FallbackRules.ORDER_DATE,
                            "ORDER_DATE"),
                    "dd-MM-yyyy");

    private static final String HEADER_A =
            "EMP_NO,FROM_DATE,TO_DATE,PAY_DAYS,LWP_DAYS,HALF_PAY_DAYS\r\n";

    /** Members 1001 at unit 10 (enabled) and 2002 at unit 20 (not enabled). */
    private static Optional<FallbackRules.Member> member(String eis) {
        return switch (eis) {
            case "1001" -> Optional.of(new FallbackRules.Member(1L, 10L));
            case "2002" -> Optional.of(new FallbackRules.Member(2L, 20L));
            default -> Optional.empty();
        };
    }

    private static List<String> validate(
            String text, FallbackEnablement.Type type, FallbackRules.Format format) {
        List<FallbackRecord> records = FallbackRules.read(text, type, format);
        return FallbackRules.validate(records, type, FEB, FallbackRulesTest::member, Set.of(10L));
    }

    private static GeneralErrorCode code(Runnable call) {
        try {
            call.run();
        } catch (FciException ex) {
            return (GeneralErrorCode) ex.getErrorCode();
        }
        throw new AssertionError("expected an FciException");
    }

    @Test
    void csvFollowsRfc4180() {
        List<List<String>> rows =
                FallbackRules.parseCsv("a,\"b,c\",\"d\"\"e\"\r\n1,2,\"multi\nline\"\n");
        assertThat(rows)
                .containsExactly(List.of("a", "b,c", "d\"e"), List.of("1", "2", "multi\nline"));
    }

    @Test
    void D_2_withoutAConfiguredLayoutNothingIsRead() {
        assertThat(
                        code(
                                () ->
                                        FallbackRules.read(
                                                HEADER_A + "1001,01-02-2026,28-02-2026,28,0,0",
                                                A,
                                                new FallbackRules.Format(Map.of(), null))))
                .isEqualTo(GeneralErrorCode.FALLBACK_FORMAT_NOT_CONFIGURED);
    }

    @Test
    void SRS_G_46_aFileNotInTheExportFormatIsRejectedAsAWhole() {
        assertThat(code(() -> FallbackRules.read("EMP_NO,SOMETHING\r\n1001,x", A, FORMAT_A)))
                .as("required column missing")
                .isEqualTo(GeneralErrorCode.FALLBACK_FORMAT);
        assertThat(code(() -> FallbackRules.read(HEADER_A, A, FORMAT_A)))
                .as("no record")
                .isEqualTo(GeneralErrorCode.FALLBACK_FORMAT);
        // Headers are matched case-insensitively and in any order.
        assertThat(
                        FallbackRules.read(
                                "lwp_days,emp_no,to_date,from_date,pay_days\n"
                                        + "0,1001,28-02-2026,01-02-2026,28\n",
                                A,
                                FORMAT_A))
                .singleElement()
                .satisfies(r -> assertThat(r.employeeNumber()).isEqualTo("1001"));
    }

    @Test
    void SRS_EH_11_eachRecordIsAcceptedOrRejectedWithItsReason() {
        List<String> reasons =
                validate(
                        HEADER_A
                                + "1001,01-02-2026,14-02-2026,14,0,0\r\n" // accepted
                                + "9999,01-02-2026,28-02-2026,28,0,0\r\n" // not on the master
                                + "2002,01-02-2026,28-02-2026,28,0,0\r\n" // office not enabled
                                + "1001,10-02-2026,20-02-2026,10,1,0\r\n" // overlaps line 1
                                + "1001,01-02-2026,14-02-2026,14,0,0\r\n" // duplicate of line 1
                                + "1001,25-01-2026,28-02-2026,28,0,0\r\n" // outside the month
                                + "1001,15-02-2026,16-02-2026,5,0,0\r\n" // 5 days in a 2-day period
                                + "1001,31-02-2026,28-02-2026,28,0,0\r\n",
                        A,
                        FORMAT_A);
        assertThat(reasons.get(0)).isNull();
        assertThat(reasons.get(1)).startsWith("EMPLOYEE_NOT_FOUND");
        assertThat(reasons.get(2)).startsWith("OFFICE_NOT_ENABLED");
        assertThat(reasons.get(3)).startsWith("OVERLAP");
        assertThat(reasons.get(4)).startsWith("DUPLICATE");
        assertThat(reasons.get(5)).startsWith("PERIOD");
        assertThat(reasons.get(6)).startsWith("DAYS");
        assertThat(reasons.get(7)).as("31 Feb is not a date (strict)").startsWith("UNREADABLE");
    }

    // SRS-1-72: suspension, absconding and revocation orders come through X-07 only as fallback
    // (b).
    // SC-16-0070: suspension, absconding and revocation orders come through X-07 fallback (b) only.
    @Test
    void BR_INT_011_fallbackBCarriesOnlyOrderRecordsWithTheirParticulars() {
        String header = "EMP_NO,EVENT,EFFECTIVE_DATE,ORDER_NO,ORDER_DATE\n";
        List<String> reasons =
                validate(
                        header
                                + "1001,suspension,05-02-2026,RO/14,04-02-2026\n" // accepted (type
                                // case-insensitive)
                                + "1001,PROMOTION,05-02-2026,RO/15,04-02-2026\n" // not a fallback
                                // (b) item
                                + "1001,REVOCATION,05-02-2026,,04-02-2026\n" // order number missing
                                + "1001,ABSCONDING,05-03-2026,RO/16,04-02-2026\n",
                        B,
                        FORMAT_B); // effective after the month
        assertThat(reasons.get(0)).isNull();
        assertThat(reasons.get(1)).startsWith("WRONG_TYPE");
        assertThat(reasons.get(2)).startsWith("ORDER");
        assertThat(reasons.get(3)).startsWith("PERIOD");
    }

    @Test
    void anAttendanceFileImpliesItsRecordType() {
        assertThat(
                        FallbackRules.read(
                                        HEADER_A + "1001,01-02-2026,28-02-2026,28,0,0", A, FORMAT_A)
                                .get(0)
                                .recordType())
                .isEqualTo("ATTENDANCE_LEAVE");
        assertThatThrownBy(() -> FallbackRules.read("EMP_NO\n1001", B, FORMAT_B))
                .isInstanceOf(FciException.class);
    }

    @Test
    void SRS_EH_11_missingValuesAreReasonsNotCrashes() {
        List<String> reasons =
                validate(
                        HEADER_A
                                + ",01-02-2026,14-02-2026,14,0,0\r\n" // no employee number
                                + "1001,01-02-2026,10-02-2026,,0,0\r\n" // pay days missing
                                + "1001,11-02-2026,12-02-2026,2,-1,0\r\n" // negative leave
                                + "1001,13-02-2026,14-02-2026,2,0,x\r\n" // not a number
                                + "1001,20-02-2026,15-02-2026,2,0,0\r\n" // ends before it starts
                                + "1001,15-02-2026,16-02-2026,2,0,0\r\n" // accepted
                                + "1001,25-02-2026,26-02-2026,2,0,0\r\n" // accepted, after a gap
                                + ",,,,,\r\n", // a blank trailing row is not a record
                        A,
                        FORMAT_A);
        assertThat(reasons).hasSize(7);
        assertThat(reasons.get(0)).startsWith("EMPLOYEE_NOT_FOUND: no employee number");
        assertThat(reasons.get(1)).startsWith("DAYS: pay days and leave without pay");
        assertThat(reasons.get(2)).startsWith("DAYS: a day count is negative");
        assertThat(reasons.get(3)).startsWith("UNREADABLE");
        assertThat(reasons.get(4)).startsWith("PERIOD");
        assertThat(reasons.get(5)).isNull();
        assertThat(reasons.get(6)).isNull();
    }

    @Test
    void SRS_G_46_aShortRowReadsItsMissingColumnsAsEmpty() {
        FallbackRecord r = FallbackRules.read(HEADER_A + "1001,01-02-2026", A, FORMAT_A).get(0);
        assertThat(r.employeeNumber()).isEqualTo("1001");
        assertThat(r.periodTo()).isNull();
        assertThat(r.payDays()).isNull();
    }

    @Test
    void SRS_G_46_aFileOverTheRecordLimitIsRejectedAsAWhole() {
        String row = "1001,01-02-2026,28-02-2026,28,0,0\n";
        String csv = HEADER_A + row.repeat(FallbackRules.MAX_RECORDS + 1);
        assertThat(code(() -> FallbackRules.read(csv, A, FORMAT_A)))
                .isEqualTo(GeneralErrorCode.FALLBACK_FORMAT);
        assertThat(
                        FallbackRules.read(
                                HEADER_A + row.repeat(FallbackRules.MAX_RECORDS), A, FORMAT_A))
                .as("exactly the limit is read")
                .hasSize(FallbackRules.MAX_RECORDS);
    }

    @Test
    void csvEdgeCases() {
        assertThat(FallbackRules.parseCsv("a,\"\",c\r")).containsExactly(List.of("a", "", "c"));
        assertThat(FallbackRules.parseCsv("x\ry")).containsExactly(List.of("x"), List.of("y"));
        assertThat(FallbackRules.parseCsv("")).isEmpty();
        assertThat(FallbackRules.parseCsv("a,b,")).containsExactly(List.of("a", "b", ""));
    }

    @Test
    void BR_INT_011_anOrderNeedsItsDateAndEffectiveDate() {
        String header = "EMP_NO,EVENT,EFFECTIVE_DATE,ORDER_NO,ORDER_DATE\n";
        List<String> reasons =
                validate(
                        header
                                + "1001,SUSPENSION,05-02-2026,RO/1,\n"
                                + "1001,SUSPENSION,,RO/2,04-02-2026\n",
                        B,
                        FORMAT_B);
        assertThat(reasons).allSatisfy(r -> assertThat(r).startsWith("ORDER"));
    }

    @Test
    void SRS_G_46_aFileOfBlankRowsHoldsNoRecordAndIsRejectedAsAWhole() {
        assertThat(code(() -> FallbackRules.read(HEADER_A + ",,,,,\r\n , ,,,,\r\n", A, FORMAT_A)))
                .isEqualTo(GeneralErrorCode.FALLBACK_FORMAT);
    }

    /**
     * R-ASCII (CLAUDE.md; X-07 l.849): the contents of an uploaded file are outside the request
     * guard, so a cell holding a non-ASCII character (here the U+FFFD a byte above 127 decodes to,
     * or a Devanagari letter) rejects its record with the field named; the other records are read,
     * and the kept source line holds only printable ASCII.
     */
    @Test
    void R_ASCII_aNonAsciiCellInAnX07UploadIsRejectedPerRecord() {
        String header = "EMP_NO,EVENT,EFFECTIVE_DATE,ORDER_NO,ORDER_DATE\n";
        String text =
                header
                        + "1001,SUSPENSION,05-02-2026,RO/14,04-02-2026\n"
                        + "1001,SUSPENSION,05-02-2026,RO/1\uFFFD5,04-02-2026\n"
                        + "1001,SUSPENSION,05-02-2026,\u0906\u0926\u0947\u0936,04-02-2026\n"
                        + "1001\t,SUSPENSION,06-02-2026,RO/17,04-02-2026\n";
        List<String> reasons = validate(text, B, FORMAT_B);
        assertThat(reasons.get(0)).isNull();
        assertThat(reasons.get(1)).isEqualTo("UNREADABLE: the value of orderNo could not be read");
        assertThat(reasons.get(2)).isEqualTo("UNREADABLE: the value of orderNo could not be read");
        // A tab around a value is white space, stripped: the value itself is ASCII.
        assertThat(reasons.get(3)).isNull();
        List<FallbackRecord> records = FallbackRules.read(text, B, FORMAT_B);
        assertThat(records.get(1).orderNo()).isNull();
        assertThat(records.get(1).raw()).isEqualTo("1001,SUSPENSION,05-02-2026,RO/1?5,04-02-2026");
        assertThat(records.get(2).raw()).isEqualTo("1001,SUSPENSION,05-02-2026,????,04-02-2026");
    }

    /** R-ASCII: the source line keeps space and tilde (the printable bounds) and marks the rest. */
    @Test
    void R_ASCII_theKeptSourceLineMarksEveryCharacterOutsidePrintableAscii() {
        assertThat(FallbackRules.ascii(" a~")).isEqualTo(" a~");
        assertThat(FallbackRules.ascii("a\u007Fb\u001Fc\u00E9")).isEqualTo("a?b?c?");
        assertThat(FallbackRules.ascii("")).isEmpty();
    }

    @Test
    void SRS_EH_11_aLineWithSeveralUnreadableValuesIsRejectedForTheFirst() {
        List<String> reasons =
                validate(HEADER_A + "1001,31-02-2026,30-02-2026,x,y,0\r\n", A, FORMAT_A);
        assertThat(reasons)
                .containsExactly("UNREADABLE: the value of periodFrom could not be read");
    }

    @Test
    void SRS_EH_11_anAttendancePeriodNeedsBothEndsInsideThePayrollMonth() {
        List<String> reasons =
                validate(
                        HEADER_A
                                + "1001,,28-02-2026,28,0,0\r\n" // no first day
                                + "1001,01-02-2026,,28,0,0\r\n" // no last day
                                + "1001,20-02-2026,05-03-2026,9,0,0\r\n" // ends in March
                                + "1001,01-02-2026,28-02-2026,28,0,0\r\n", // the whole month
                        A,
                        FORMAT_A);
        assertThat(reasons.subList(0, 3)).allSatisfy(r -> assertThat(r).startsWith("PERIOD"));
        assertThat(reasons.get(3)).isNull();
    }

    @Test
    void SRS_EH_11_leaveWithoutPayIsRequiredBesidePayDays() {
        assertThat(validate(HEADER_A + "1001,01-02-2026,10-02-2026,10,,0\r\n", A, FORMAT_A))
                .containsExactly("DAYS: pay days and leave without pay are required");
    }

    @Test
    void SRS_EH_11_periodsBeforeOrAfterAnEarlierOneOfTheSameEmployeeDoNotOverlap() {
        List<String> reasons =
                validate(
                        HEADER_A
                                + "1001,15-02-2026,20-02-2026,6,0,0\r\n"
                                + "1001,01-02-2026,10-02-2026,10,0,0\r\n" // wholly before
                                + "1001,21-02-2026,28-02-2026,8,0,0\r\n" // wholly after
                                + "1001,10-02-2026,15-02-2026,6,0,0\r\n", // touches both
                        A,
                        FORMAT_A);
        assertThat(reasons.subList(0, 3)).containsOnlyNulls();
        assertThat(reasons.get(3)).startsWith("OVERLAP");
    }

    @Test
    void SRS_G_46_anAttendanceFileWithARecordTypeColumnIsCheckedAgainstIt() {
        Map<String, String> columns = new HashMap<>(FORMAT_A.columns());
        columns.put(FallbackRules.RECORD_TYPE, "EVENT");
        FallbackRules.Format typed = new FallbackRules.Format(columns, "dd-MM-yyyy");
        String header = "EMP_NO,EVENT,FROM_DATE,TO_DATE,PAY_DAYS,LWP_DAYS\r\n";
        List<String> reasons =
                validate(
                        header
                                + "1001,attendance_leave,01-02-2026,14-02-2026,14,0\r\n"
                                + "1001,SUSPENSION,15-02-2026,28-02-2026,14,0\r\n",
                        A,
                        typed);
        assertThat(reasons.get(0)).as("the type is read case-insensitively").isNull();
        assertThat(reasons.get(1)).startsWith("WRONG_TYPE");
    }

    @Test
    void SRS_EH_11_anOrderRecordWithoutItsTypeIsRejectedWithItsReasonNotACrash() {
        String header = "EMP_NO,EVENT,EFFECTIVE_DATE,ORDER_NO,ORDER_DATE\n";
        assertThat(validate(header + "1001,,05-02-2026,RO/14,04-02-2026\n", B, FORMAT_B))
                .singleElement()
                .satisfies(r -> assertThat(r).startsWith("WRONG_TYPE"));
    }

    @Test
    void D_2_aLayoutWithoutADatePatternIsNotConfigured() {
        assertThat(new FallbackRules.Format(Map.of("a", "A"), " ").isConfigured()).isFalse();
        assertThat(new FallbackRules.Format(Map.of("a", "A"), null).isConfigured()).isFalse();
        assertThat(new FallbackRules.Format(Map.of("a", "A"), "dd-MM-yyyy").isConfigured())
                .isTrue();
        assertThat(new FallbackRules.Format(null, "dd-MM-yyyy").isConfigured()).isFalse();
        assertThat(code(() -> FallbackRules.read(HEADER_A, A, null)))
                .isEqualTo(GeneralErrorCode.FALLBACK_FORMAT_NOT_CONFIGURED);
    }
}
