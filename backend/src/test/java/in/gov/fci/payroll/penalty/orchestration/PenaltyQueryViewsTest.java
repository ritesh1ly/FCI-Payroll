package in.gov.fci.payroll.penalty.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.cols;
import static in.gov.fci.payroll.ScriptedJdbc.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * I-06 / PN-17 views (SRS-14-1, SRS-14-16): an order by amount has no period and no instalment
 * count, one by period has both; a revoke by amount has no period; a request not yet submitted has
 * no time.
 */
class PenaltyQueryViewsTest {

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final Authorization policy = mock(Authorization.class);
    private final AccessContext context = mock(AccessContext.class);
    private final PenaltyQueryService service =
            new PenaltyQueryService(db.named, mock(OrgDirectory.class), policy);

    PenaltyQueryViewsTest() {
        when(context.locationId()).thenReturn(1L);
        when(policy.isAtHeadquarters(context)).thenReturn(true);
    }

    private static Map<String, Object> order(Integer instalments, LocalDate from, LocalDate to) {
        return row(
                "order_id", 4L,
                "penalty_ref", "PEN-4",
                "emp_num", "E4",
                "first_name", "Asha",
                "last_name", "Rao",
                "penalty_nature", "RECOVERY",
                "order_reference", "O/4",
                "penalty_source", "ENTERED",
                "total_recovery_amount", new BigDecimal("3000"),
                "instalment_count", instalments,
                "period_from", from == null ? null : Date.valueOf(from),
                "period_to", to == null ? null : Date.valueOf(to),
                "recovered", BigDecimal.ZERO,
                "outstanding", new BigDecimal("3000"),
                "order_status", "ACTIVE",
                "is_backdated", "N");
    }

    @Test
    void SRS_14_1_anOrderByAmountHasNoPeriodAndOneByPeriodHasBoth() {
        db.rows("o.order_status, o.is_backdated", order(null, null, null))
                .rows(
                        "FROM penalty_revoke WHERE order_id",
                        cols(
                                1L,
                                "PARTIAL",
                                new BigDecimal("500"),
                                null,
                                null,
                                "R/1",
                                "r",
                                "PENDING"),
                        cols(
                                2L,
                                "PARTIAL",
                                null,
                                Date.valueOf(LocalDate.of(2026, 11, 1)),
                                Date.valueOf(LocalDate.of(2026, 12, 31)),
                                "R/2",
                                "r",
                                "APPROVED"));
        PenaltyQueryService.Detail byAmount = service.detail(context, 4L);
        assertThat(byAmount.order().instalments()).isNull();
        assertThat(byAmount.order().periodFrom()).isNull();
        assertThat(byAmount.revokes().get(0).periodFrom()).isNull();
        assertThat(byAmount.revokes().get(1).periodTo()).isEqualTo(LocalDate.of(2026, 12, 31));

        db.rows(
                "o.order_status, o.is_backdated",
                order(3, LocalDate.of(2026, 11, 1), LocalDate.of(2027, 1, 31)));
        PenaltyQueryService.OrderRow byPeriod = service.detail(context, 4L).order();
        assertThat(byPeriod.instalments()).isEqualTo(3);
        assertThat(byPeriod.periodTo()).isEqualTo(LocalDate.of(2027, 1, 31));
    }

    @Test
    void SRS_14_16_aRequestWithoutASubmissionTimeShowsNone() {
        db.rows(
                "q.return_reason, q.decision_reason",
                row(
                        "approval_id",
                        7L,
                        "emp_num",
                        "E4",
                        "first_name",
                        "Asha",
                        "last_name",
                        null,
                        "impact_value",
                        null,
                        "status",
                        "PENDING_ATTACHMENT",
                        "submitted_by",
                        "maker@x",
                        "submitted_at",
                        null,
                        "return_reason",
                        null,
                        "decision_reason",
                        null));
        assertThat(service.requests(context, new PageSpec(0, 20)).items().get(0).submittedAt())
                .isNull();
    }
}
