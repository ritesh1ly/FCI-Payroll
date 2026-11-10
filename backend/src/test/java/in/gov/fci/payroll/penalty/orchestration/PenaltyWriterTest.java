package in.gov.fci.payroll.penalty.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.penalty.business.model.PenaltyOrder;
import in.gov.fci.payroll.penalty.persistence.PenaltyRepositories;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * SRS-14-15, BR-PEN2-007 (US-GAP-229): the Penalty ID comes from the sequence {@code
 * penalty_ref_seq} with the year of India's date; an order recorded on I-06 keeps the ID it was
 * given at submission, an order from HRMS (no ID yet) is numbered when its task is approved.
 */
class PenaltyWriterTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PenaltyRepositories.Orders orders = mock(PenaltyRepositories.Orders.class);
    private final PenaltyWriter writer =
            new PenaltyWriter(
                    orders, mock(PenaltyRepositories.Lines.class), mock(AuditService.class), jdbc);

    PenaltyWriterTest() {
        when(orders.save(any(PenaltyOrder.class))).thenAnswer(i -> i.getArgument(0));
    }

    /** A censure-like order that recovers nothing (no amount, instalments or start month). */
    private static PenaltyWriter.Approved approved(String penaltyRef) {
        return new PenaltyWriter.Approved(
                7L,
                "CENSURE",
                "ORD/1",
                LocalDate.of(2026, 12, 31),
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                penaltyRef);
    }

    @Test
    void SRS_14_15_thePenaltyIdIsTheSequenceWithTheYear() {
        when(jdbc.queryForObject(contains("penalty_ref_seq.NEXTVAL"), eq(Long.class)))
                .thenReturn(42L);
        assertThat(PenaltyWriter.nextPenaltyId(jdbc, LocalDate.of(2027, 1, 1)))
                .isEqualTo("PEN-2027-00042");
        assertThat(PenaltyWriter.nextPenaltyId(jdbc, LocalDate.of(2026, 12, 31)))
                .isEqualTo("PEN-2026-00042");
    }

    @Test
    void SRS_14_15_aSequenceWithoutAValueIsNeverNumberedSilently() {
        when(jdbc.queryForObject(contains("penalty_ref_seq.NEXTVAL"), eq(Long.class)))
                .thenReturn(null);
        assertThatThrownBy(() -> PenaltyWriter.nextPenaltyId(jdbc, LocalDate.of(2026, 4, 1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void SRS_14_15_anOrderNumberedAtApprovalTakesTheYearOfIndiasDate() {
        when(jdbc.queryForObject(contains("penalty_ref_seq.NEXTVAL"), eq(Long.class)))
                .thenReturn(5L);
        // 31 December 19:00 UTC is already 1 January (00:30) in India.
        PenaltyOrder order =
                writer.create(approved(null), "ddo@x", 9L, Instant.parse("2026-12-31T19:00:00Z"));
        assertThat(order.getPenaltyRef()).isEqualTo("PEN-2027-00005");
        // One minute before India's midnight it is still the old year.
        PenaltyOrder late =
                writer.create(approved(null), "ddo@x", 9L, Instant.parse("2026-12-31T18:29:00Z"));
        assertThat(late.getPenaltyRef()).isEqualTo("PEN-2026-00005");
    }

    @Test
    void SRS_14_15_anOrderRecordedOnI06KeepsTheIdGivenAtSubmission() {
        PenaltyOrder order =
                writer.create(
                        approved("PEN-2026-00011"),
                        "ddo@x",
                        9L,
                        Instant.parse("2027-01-02T10:00:00Z"));
        assertThat(order.getPenaltyRef()).isEqualTo("PEN-2026-00011");
        verify(jdbc, never()).queryForObject(contains("penalty_ref_seq"), eq(Long.class));
    }

    /** An order with an amount but without its instalments or its start month. */
    private static PenaltyWriter.Approved partly(Integer instalments, String start) {
        return new PenaltyWriter.Approved(
                7L,
                "RECOVERY",
                "ORD/2",
                LocalDate.of(2026, 5, 15),
                new java.math.BigDecimal("1200"),
                instalments,
                start,
                null,
                false,
                null,
                null,
                "PEN-2026-00021");
    }

    @Test
    void SRS_14_1_anOrderRecoversOnlyWithItsAmountInstalmentsAndStartMonth() {
        PenaltyRepositories.Lines lines = mock(PenaltyRepositories.Lines.class);
        PenaltyWriter full = new PenaltyWriter(orders, lines, mock(AuditService.class), jdbc);
        // Without the instalments, or without the start month, nothing is scheduled and the order
        // runs from its effective date.
        for (PenaltyWriter.Approved a : List.of(partly(null, "202606"), partly(2, null))) {
            PenaltyOrder order = full.create(a, "ddo@x", 9L, Instant.parse("2026-05-20T05:00:00Z"));
            assertThat(order.getPeriodFrom()).isEqualTo(LocalDate.of(2026, 5, 15));
        }
        verify(lines, never()).save(any());
        // With all three the schedule starts in the start month.
        PenaltyOrder recovering =
                full.create(
                        partly(2, "202606"), "ddo@x", 9L, Instant.parse("2026-05-20T05:00:00Z"));
        assertThat(recovering.getPeriodFrom()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(recovering.getPeriodTo()).isEqualTo(LocalDate.of(2026, 7, 31));
        verify(lines, times(2)).save(any());
    }
}
