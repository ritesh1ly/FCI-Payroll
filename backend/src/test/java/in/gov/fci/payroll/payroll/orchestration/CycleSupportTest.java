package in.gov.fci.payroll.payroll.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.security.AccessContext;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What the payroll-cycle services share (plan 05; SRS-15-1 cycle unit of the office, SRS-G-2 audit
 * columns): the caller's cycle unit and the JDBC values as H2 and Oracle hand them over.
 */
class CycleSupportTest {

    private static AccessContext at(Long locationId, String login) {
        return new AccessContext(
                2L,
                login,
                "User",
                "s",
                "P",
                locationId,
                null,
                List.of(),
                Map.of(),
                Set.of(),
                Set.of(),
                locationId == null ? Set.of() : Set.of(locationId));
    }

    @Test
    void SRS_15_1_theCallerWorksInTheCycleUnitOfItsOffice() {
        OrgDirectory org = mock(OrgDirectory.class);
        OrgLocation unit = mock(OrgLocation.class);
        when(org.cycleUnitOf(7L)).thenReturn(Optional.of(unit));
        when(org.cycleUnitOf(8L)).thenReturn(Optional.empty());
        assertThat(CycleSupport.unit(org, at(7L, "u"))).isSameAs(unit);
        assertThatThrownBy(() -> CycleSupport.unit(org, at(null, "u")))
                .isInstanceOf(FciException.class)
                .hasMessage("COM-403-DENIED");
        assertThatThrownBy(() -> CycleSupport.unit(org, at(8L, "u")))
                .isInstanceOf(FciException.class)
                .hasMessage("CYC-422-UNIT");
    }

    @Test
    void SRS_G_2_theLoginIsCutToTheFiftyOfTheAuditColumns() {
        assertThat(CycleSupport.login(at(1L, "x".repeat(50)))).hasSize(50);
        assertThat(CycleSupport.login(at(1L, "x".repeat(51)))).hasSize(50);
        assertThat(CycleSupport.login(at(1L, "maker"))).isEqualTo("maker");
    }

    @Test
    void SRS_G_2_dateTimestampAndNumberColumnsAreReadWhateverTheDriverHandsOver() {
        LocalDate day = LocalDate.of(2026, 10, 26);
        assertThat(CycleSupport.date(java.sql.Date.valueOf(day))).isEqualTo(day);
        assertThat(CycleSupport.date(Timestamp.valueOf(day.atTime(10, 0)))).isEqualTo(day);
        assertThat(CycleSupport.date(day)).isEqualTo(day);
        assertThat(CycleSupport.date(LocalDateTime.of(2026, 10, 26, 23, 59))).isEqualTo(day);
        assertThat(CycleSupport.date(null)).isNull();
        assertThat(CycleSupport.date("2026-10-26")).isNull();
        Instant now = Instant.parse("2026-10-26T05:00:00Z");
        assertThat(CycleSupport.instant(Timestamp.from(now))).isEqualTo(now);
        assertThat(CycleSupport.instant(null)).isNull();
        assertThat(CycleSupport.text(new BigDecimal("1E+3"))).isEqualTo("1000");
        assertThat(CycleSupport.text(42)).isEqualTo("42");
        assertThat(CycleSupport.text(null)).isNull();
    }

    @Test
    void SRS_G_1_onlyTheLatestItemStillWaitingIsTheOpenDecision() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ApprovalStatuses statuses = mock(ApprovalStatuses.class);
        when(jdbc.queryForList(anyString(), eq(Long.class), eq("e"), eq("none")))
                .thenReturn(List.of());
        when(jdbc.queryForList(anyString(), eq(Long.class), eq("e"), eq("waiting")))
                .thenReturn(List.of(9L, 4L));
        when(jdbc.queryForList(anyString(), eq(Long.class), eq("e"), eq("decided")))
                .thenReturn(List.of(8L));
        when(jdbc.queryForList(anyString(), eq(Long.class), eq("e"), eq("unknown")))
                .thenReturn(List.of(7L));
        when(statuses.of(List.of(9L))).thenReturn(Map.of(9L, ApprovalStatus.PENDING_APPROVAL));
        when(statuses.of(List.of(8L))).thenReturn(Map.of(8L, ApprovalStatus.APPROVED));
        when(statuses.of(List.of(7L))).thenReturn(Map.of());
        assertThat(CycleSupport.pending(jdbc, statuses, "e", "none")).isNull();
        assertThat(CycleSupport.pending(jdbc, statuses, "e", "waiting")).isEqualTo(9L);
        assertThat(CycleSupport.pending(jdbc, statuses, "e", "decided")).isNull();
        assertThat(CycleSupport.pending(jdbc, statuses, "e", "unknown")).isNull();
        assertThat(CycleSupport.orZero(null)).isZero();
        assertThat(CycleSupport.orZero(5L)).isEqualTo(5);
    }

    @Test
    void SRS_G_13_aRowThatCannotBeWrittenFailsTheExportWithItsOwnError() throws Exception {
        ExportSink sink = mock(ExportSink.class);
        CycleSupport.exporting(() -> CycleSupport.row(sink, "a", 1));
        org.mockito.Mockito.verify(sink).row("a", 1);
        IOException disk = new IOException("disk full");
        doThrow(disk).when(sink).row(any(Object[].class));
        assertThatThrownBy(() -> CycleSupport.exporting(() -> CycleSupport.row(sink, "b")))
                .isSameAs(disk);
    }
}
