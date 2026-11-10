package in.gov.fci.payroll.labour.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.labour.business.NormRules.Slab;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** M-06 Depot Incentive Norms as listed (plan 20 phase 20d; SRS-3-12): slab bands read back. */
class NormServiceListTest {

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final NormService service =
            new NormService(
                    db.jdbc,
                    db.named,
                    mock(Authorization.class),
                    mock(OrgDirectory.class),
                    mock(LabourDepots.class),
                    mock(MakerChecker.class),
                    mock(SystemParameters.class),
                    mock(AuditService.class),
                    mock(in.gov.fci.payroll.configuration.api.Circulars.class));

    /** The open top band (range_to NULL) is read as open, not as ending at 0. */
    @Test
    void SRS_3_12_theOpenTopBandIsListedAsOpen() {
        db.rows(
                        "n.mou_reference, q.status",
                        row(
                                "norm_id",
                                3L,
                                "location_code",
                                "DPT01",
                                "location_name",
                                "Depot One",
                                "version_no",
                                1,
                                "effective_from",
                                Date.valueOf(LocalDate.of(2026, 4, 1)),
                                "effective_to",
                                null,
                                "handling_norm",
                                250L,
                                "height_norm",
                                20L,
                                "lead_norm",
                                0L,
                                "min_labour_number",
                                10L,
                                "min_labour_slab_weight",
                                new BigDecimal("8.5"),
                                "mou_reference",
                                null,
                                "status",
                                null,
                                "mou_circular",
                                "HQ/MOU/2026/1"))
                .rows(
                        "FROM labour_above_norm_slab",
                        row(
                                "slab_table_ref",
                                "BAND_1",
                                "band_order",
                                1,
                                "range_from",
                                1L,
                                "range_to",
                                30L,
                                "slab_rate_percent",
                                new BigDecimal("8")),
                        row(
                                "slab_table_ref",
                                "BAND_1",
                                "band_order",
                                2,
                                "range_from",
                                31L,
                                "range_to",
                                null,
                                "slab_rate_percent",
                                new BigDecimal("15")));
        NormService.NormSet set =
                service.page(mock(AccessContext.class), null, new PageSpec(0, 20)).items().get(0);
        assertThat(set.approvalState()).isEqualTo("APPROVED");
        assertThat(set.depotCode()).as("the key of the depot's History record").isEqualTo("DPT01");
        assertThat(set.effectiveTo()).isNull();
        assertThat(set.slabs()).extracting(Slab::to).containsExactly(30L, null);
    }
}
