package in.gov.fci.payroll.employee.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The I-21 applier (plan 16 D16-4; SRS-1-28, SRS-1-19, SRS-1-22): every supported column is read
 * and written by its own constant statement, the value checked against the column's type; a field
 * without a mapped column, or of a column not supported, is not applicable; a life-cycle event
 * field is (16f-1); the DOB also writes the retirement date when the age is set.
 */
class HrmsChangeApplierTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AuditService audit = mock(AuditService.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private final HrmsChangeApplier applier = new HrmsChangeApplier(jdbc, audit, parameters);

    private Map<String, Object> field(String code, String column) {
        Map<String, Object> f = new HashMap<>();
        f.put("FIELD_CODE", code);
        f.put("FIELD_SOURCE_ID", column == null ? null : 5L);
        when(jdbc.queryForList(anyString(), eq(String.class), anyLong()))
                .thenReturn(column == null ? List.of() : List.of(column));
        return f;
    }

    private void current(Object value) {
        Map<String, Object> row = new HashMap<>();
        row.put("V", value);
        when(jdbc.queryForMap(anyString(), anyLong())).thenReturn(row);
        when(jdbc.queryForObject(anyString(), eq(String.class), anyLong())).thenReturn("100041");
    }

    @ParameterizedTest
    @CsvSource({
        "PAN, pan_number, ABCPS1234K",
        "UAN, uan_number, 100200300400",
        "EPFO_MEMBER_ID, epfo_member_id, DLCPM00123450000012345",
        "EPS_NUMBER, eps_number, EPS-DL-1",
        "EPS_JOIN_DATE, eps_join_date, 2001-07-01",
        "PRAN, pran_number, 110012345678",
        "BANK_ACCOUNT, bank_account_number, 30012345678",
        "BASIC_PAY, current_basic, 61200",
        "GENDER, gender, F"
    })
    void SRS_1_28_eachColumnIsWrittenByItsOwnStatement(String code, String column, String value) {
        Map<String, Object> f = field(code, column);
        current(null);
        assertThat(applier.applicable(f)).isTrue();
        assertThat(applier.current(f, 7L)).isNull();
        applier.apply(f, 7L, value, "checker", 9L);
        verify(jdbc)
                .update(
                        eq(
                                "UPDATE employee SET "
                                        + column
                                        + " = ?, row_version = row_version + 1 WHERE employee_id"
                                        + " = ?"),
                        any(),
                        eq(7L));
    }

    /** SRS-1-9 / BR-EMPM-003: a PAN another employee holds is refused, never written. */
    @Test
    void SRS_1_9_aPanAnotherEmployeeHoldsIsRefusedAndNotWritten() {
        Map<String, Object> f = field("PAN", "pan_number");
        current("ABCPS1234K");
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("ABCPT5678L"), eq(7L)))
                .thenReturn(1);
        assertThatThrownBy(() -> applier.requireUnique(f, 7L, " ABCPT5678L "))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ApiErrorCode.I21_PAN_HELD));
        assertThatThrownBy(() -> applier.apply(f, 7L, "ABCPT5678L", "checker", 9L))
                .isInstanceOf(FciException.class);
        verify(jdbc, never()).update(anyString(), any(), eq(7L));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("ABCPU9012M"), eq(7L)))
                .thenReturn(0);
        applier.requireUnique(f, 7L, "ABCPU9012M");
        applier.requireUnique(f, 7L, null);
        Map<String, Object> uan = field("UAN", "uan_number");
        applier.requireUnique(uan, 7L, "ABCPT5678L");
    }

    @Test
    void SRS_1_24_aDobThatRetiresTheMemberThisMonthOrNextAlertsBeforeTheLock() {
        java.util.List<Object> published = new java.util.ArrayList<>();
        applier.setEvents(published::add);
        Map<String, Object> f = field("DATE_OF_BIRTH", "birth_date");
        current(java.sql.Date.valueOf("1980-05-20"));
        when(parameters.configured(SystemParameters.Code.RETIREMENT_AGE))
                .thenReturn(OptionalLong.of(60));
        java.time.YearMonth next =
                java.time.YearMonth.now(java.time.ZoneId.of("Asia/Kolkata")).plusMonths(1);
        // Born on the 15th: retires at the end of the month of the 60th birthday, i.e. next month.
        applier.apply(f, 7L, next.atDay(15).minusYears(60).toString(), "checker", 9L);
        assertThat(published)
                .singleElement()
                .isEqualTo(
                        new in.gov.fci.payroll.employee.api.EmployeeEvents.RetirementSoon(
                                7L, next.atEndOfMonth(), 9L, "checker"));
        // Retiring years away: no alert.
        applier.apply(f, 7L, next.atDay(15).minusYears(50).toString(), "checker", 9L);
        assertThat(published).hasSize(1);
    }

    // SC-16-0104: the retirement date is recalculated when an approved DOB change applies.
    @Test
    void SRS_1_22_aDobAlsoWritesTheRetirementDateOnlyWhenTheAgeIsSet() {
        Map<String, Object> f = field("DATE_OF_BIRTH", "birth_date");
        current(java.sql.Date.valueOf("1970-05-20"));
        when(parameters.configured(SystemParameters.Code.RETIREMENT_AGE))
                .thenReturn(OptionalLong.of(60));
        assertThat(applier.current(f, 7L)).isEqualTo("1970-05-20");
        applier.apply(f, 7L, "1966-05-20", "checker", null);
        verify(jdbc)
                .update(
                        "UPDATE employee SET retirement_date = ?, row_version = row_version + 1"
                                + " WHERE employee_id = ?",
                        java.sql.Date.valueOf("2026-05-31"),
                        7L);

        JdbcTemplate other = mock(JdbcTemplate.class);
        SystemParameters unset = mock(SystemParameters.class);
        when(unset.configured(SystemParameters.Code.RETIREMENT_AGE))
                .thenReturn(OptionalLong.empty());
        HrmsChangeApplier noAge = new HrmsChangeApplier(other, audit, unset);
        when(other.queryForList(anyString(), eq(String.class), anyLong()))
                .thenReturn(List.of("birth_date"));
        Map<String, Object> row = new HashMap<>();
        row.put("V", null);
        when(other.queryForMap(anyString(), anyLong())).thenReturn(row);
        noAge.apply(f, 7L, "1966-05-20", "checker", null);
        verify(other, never())
                .update(
                        eq(
                                "UPDATE employee SET retirement_date = ?, row_version = row_version"
                                        + " + 1 WHERE employee_id = ?"),
                        any(),
                        any());
    }

    @Test
    void SRS_1_28_aValueNotFittingItsColumnIsRefused() {
        Map<String, Object> basic = field("BASIC_PAY", "current_basic");
        current(new BigDecimal("50000"));
        assertThat(applier.current(basic, 7L)).isEqualTo("50000");
        for (String bad : new String[] {"0", "-5", "abc"}) {
            assertThatThrownBy(() -> applier.apply(basic, 7L, bad, "c", 1L))
                    .isInstanceOfSatisfying(
                            FciException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ApiErrorCode.I21_VALUE));
        }
        Map<String, Object> pan = field("PAN", "pan_number");
        for (String bad : new String[] {" ", "X".repeat(31)}) {
            assertThatThrownBy(() -> applier.apply(pan, 7L, bad, "c", 1L))
                    .isInstanceOf(FciException.class);
        }
        assertThatThrownBy(() -> applier.apply(pan, 7L, null, "c", 1L))
                .isInstanceOf(FciException.class);
        Map<String, Object> dob = field("DATE_OF_BIRTH", "birth_date");
        assertThatThrownBy(() -> applier.apply(dob, 7L, "20-05-1966", "c", 1L))
                .isInstanceOf(FciException.class);
    }

    @Test
    void D16_4_aFieldWithoutASupportedColumnIsNotApplicableButAnEventFieldIs() {
        Map<String, Object> none = field("CATEGORY", null);
        assertThat(applier.applicable(none)).isFalse();
        assertThat(applier.current(none, 7L)).isNull();
        assertThatThrownBy(() -> applier.apply(none, 7L, "OBC", "c", 1L))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(ApiErrorCode.I21_NOT_APPLICABLE));
        Map<String, Object> unsupported = field("CATEGORY", "category_code");
        assertThat(applier.applicable(unsupported)).isFalse();
        Map<String, Object> event = field("SUSPENSION", null);
        assertThat(applier.applicable(event)).as("16f-1: applied as an event").isTrue();
    }

    @Test
    void SRS_1_28_theCurrentValueReadsEveryDateAndNumberType() {
        Map<String, Object> f = field("EPS_JOIN_DATE", "eps_join_date");
        for (Object[] c :
                new Object[][] {
                    {java.sql.Timestamp.valueOf("2001-07-01 10:00:00"), "2001-07-01"},
                    {LocalDate.of(2001, 7, 1), "2001-07-01"},
                    {java.time.LocalDateTime.of(2001, 7, 1, 10, 0), "2001-07-01"},
                    {Integer.valueOf(5), "5"}
                }) {
            current(c[0]);
            assertThat(applier.current(f, 7L)).isEqualTo(c[1]);
        }
    }
}
