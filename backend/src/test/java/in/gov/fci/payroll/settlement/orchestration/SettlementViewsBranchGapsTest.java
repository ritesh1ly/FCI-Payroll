package in.gov.fci.payroll.settlement.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.cols;
import static in.gov.fci.payroll.ScriptedJdbc.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * O-05 case detail (SRS-10-22, O-05 l.2940): a recovery taken from gratuity shows its Headquarters
 * approval as the approval item stands; a recovery from the payroll dues has none, and one whose
 * approval item no longer reports a status is shown without one.
 */
class SettlementViewsBranchGapsTest {

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final ApprovalStatuses statuses = mock(ApprovalStatuses.class);
    private final SettlementViews views =
            new SettlementViews(
                    db.named, mock(Authorization.class), statuses, java.time.Clock.systemUTC());

    @Test
    void SRS_10_22_eachRecoveryShowsItsHeadquartersApprovalAsItStands() {
        Map<String, Object> found =
                row(
                        "settlement_case_id",
                        5L,
                        "case_reference",
                        "FNF-2026-5",
                        "emp_num",
                        "E5",
                        "first_name",
                        "Asha",
                        "last_name",
                        "Rao",
                        "location_code",
                        "DH001",
                        "location_name",
                        "Delhi",
                        "separation_type",
                        "SUPERANNUATION",
                        "separation_date",
                        Date.valueOf(LocalDate.of(2026, 9, 30)),
                        "case_stage",
                        4,
                        "settlement_id",
                        50L,
                        "settlement_status",
                        "DRAFT",
                        "net_payable",
                        BigDecimal.TEN);
        db.rows("AND c.settlement_case_id = :id", found)
                .rows(
                        "FROM fs_recovery_detail WHERE settlement_id = :sid",
                        cols(
                                1L,
                                "LICENCE_FEE",
                                "NO_DUES",
                                "AT 2026-09-30",
                                BigDecimal.ONE,
                                "PAYROLL",
                                null,
                                null,
                                null),
                        cols(
                                2L,
                                "ESTATE",
                                "QTR-1",
                                "AT 2026-09-30",
                                BigDecimal.TEN,
                                "GRATUITY",
                                "Quarter dues",
                                "ORD-1",
                                70L),
                        cols(
                                3L,
                                "ESTATE",
                                "QTR-2",
                                "AT 2026-09-30",
                                BigDecimal.TEN,
                                "GRATUITY",
                                "Quarter dues",
                                "ORD-2",
                                71L));
        when(statuses.of(any())).thenReturn(Map.of(70L, ApprovalStatus.APPROVED));
        SettlementViews.CaseDetail detail = views.detail(mock(AccessContext.class), 5L);
        assertThat(detail.recoveries())
                .extracting(SettlementViews.Recovery::hqApprovalStatus)
                .containsExactly(null, "APPROVED", null);
        assertThat(detail.recoveries())
                .extracting(SettlementViews.Recovery::hqApprovalId)
                .containsExactly(null, 70L, 71L);
        assertThat(detail.summary().separationDate()).isEqualTo(LocalDate.of(2026, 9, 30));
    }
}
