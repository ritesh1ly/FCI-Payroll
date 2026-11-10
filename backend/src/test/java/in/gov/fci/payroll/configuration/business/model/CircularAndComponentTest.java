package in.gov.fci.payroll.configuration.business.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.configuration.business.SlabBands;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The state of an M-25 circular (SRS-G-38: Pending, Active, Superseded, Withdrawn, Rejected), the
 * days a pay component is active (SRS-16-13) and a slab band as stored and read back (SRS-19-5).
 * The flows are MdmMastersIT and RuleCatalogueIT.
 */
class CircularAndComponentTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);
    private static final Instant NOW = Instant.parse("2026-10-23T05:00:00Z");

    private static CircularOrder recorded() {
        return CircularOrder.record(
                new CircularOrder.Particulars(
                        "HQ/DA/2026/3",
                        DAY,
                        "HQ_PERSONNEL",
                        1L,
                        "DA revision",
                        "DA",
                        true,
                        DAY,
                        null,
                        41L),
                "maker",
                NOW);
    }

    @Test
    void SRS_G_38_SRS_DM_19_aRecordedCircularKeepsItsParticularsAndAwaitsTheChecker() {
        CircularOrder c = recorded();
        assertThat(c.status()).isEqualTo(CircularOrder.Status.PENDING_APPROVAL);
        assertThat(c.isActive()).isFalse();
        assertThat(c.getCircularNumber()).isEqualTo("HQ/DA/2026/3");
        assertThat(c.getCircularDate()).isEqualTo(DAY);
        assertThat(c.getIssuingAuthorityCode()).isEqualTo("HQ_PERSONNEL");
        assertThat(c.getSubject()).isEqualTo("DA revision");
        assertThat(c.getCategoryCode()).isEqualTo("DA");
        assertThat(c.isAppliesAllOffices()).isTrue();
        assertThat(c.getEffectiveFrom()).isEqualTo(DAY);
        assertThat(c.getEffectiveTo()).isNull();
        assertThat(c.getSupersedesCircularId()).isEqualTo(41L);
        c.submitted(9L, 77L);
        assertThat(c.getDocumentAttachmentId()).isEqualTo(77L);
    }

    @Test
    void SRS_G_38_anApprovedCircularIsActiveARejectedOneKeepsTheReason() {
        CircularOrder approved = recorded();
        approved.decided(true, "ignored", "checker", NOW);
        assertThat(approved.isActive()).isTrue();
        assertThat(approved.getStatusReason()).isNull();
        CircularOrder rejected = recorded();
        rejected.decided(false, "Wrong number", "checker", NOW);
        assertThat(rejected.status()).isEqualTo(CircularOrder.Status.REJECTED);
        assertThat(rejected.isActive()).isFalse();
        assertThat(rejected.getStatusReason()).isEqualTo("Wrong number");
    }

    // SC-01-0156: a withdrawn circular is no longer linked to new records.
    @Test
    void SRS_G_38_aCircularIsSupersededOrWithdrawnAndThenNoLongerActive() {
        CircularOrder superseded = recorded();
        superseded.decided(true, null, "checker", NOW);
        superseded.supersede("checker", NOW);
        assertThat(superseded.status()).isEqualTo(CircularOrder.Status.SUPERSEDED);
        assertThat(superseded.isActive()).isFalse();
        CircularOrder withdrawn = recorded();
        withdrawn.decided(true, null, "checker", NOW);
        withdrawn.withdraw("Order cancelled", "checker", NOW);
        assertThat(withdrawn.status()).isEqualTo(CircularOrder.Status.WITHDRAWN);
        assertThat(withdrawn.getStatusReason()).isEqualTo("Order cancelled");
        assertThat(withdrawn.isActive()).isFalse();
    }

    private static PayComponent component(LocalDate from, LocalDate to, boolean retired) {
        PayComponent p = BeanUtils.instantiateClass(PayComponent.class);
        ReflectionTestUtils.setField(p, "effectiveFrom", from);
        ReflectionTestUtils.setField(p, "effectiveTo", to);
        ReflectionTestUtils.setField(p, "isRetired", retired);
        return p;
    }

    @Test
    void SRS_16_13_SRS_G_5_aPayComponentIsActiveInsideItsDatesBothEndsIncludedUnlessRetired() {
        PayComponent bounded = component(DAY, DAY.plusDays(30), false);
        assertThat(bounded.isActiveOn(DAY.minusDays(1))).isFalse();
        assertThat(bounded.isActiveOn(DAY)).isTrue();
        assertThat(bounded.isActiveOn(DAY.plusDays(30))).isTrue();
        assertThat(bounded.isActiveOn(DAY.plusDays(31))).isFalse();
        assertThat(component(DAY, null, false).isActiveOn(DAY.plusYears(50))).isTrue();
        assertThat(component(DAY, null, true).isActiveOn(DAY)).isFalse();
    }

    @Test
    void SRS_19_5_aSlabBandIsReadBackAsItWasStored() {
        SlabBands.Band band =
                new SlabBands.Band(
                        3,
                        new BigDecimal("100"),
                        new BigDecimal("200"),
                        new BigDecimal("5"),
                        new BigDecimal("10"),
                        "CITY-X");
        RuleSlabBand row = new RuleSlabBand(17L, band);
        assertThat(row.getRuleVersionId()).isEqualTo(17L);
        assertThat(row.band()).isEqualTo(band);
        assertThatThrownBy(() -> new RuleSlabBand(17L, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void SRS_G_38_aCircularIsLinkedWhereUsedAndTheLinkStartsActive() {
        Instant now = Instant.parse("2026-11-01T05:00:00Z");
        CircularOrderLink link =
                CircularOrderLink.link(
                        9L,
                        CircularOrderLink.Type.RULE_VERSION,
                        "rule_version",
                        "41",
                        "M-08",
                        "maker1",
                        now);
        assertThat(link.getCircularOrderId()).isEqualTo(9L);
        assertThat(link.getLinkType()).isEqualTo("RULE_VERSION");
        assertThat(link.getLinkedEntity()).isEqualTo("rule_version");
        assertThat(link.getLinkedKey()).isEqualTo("41");
        assertThat(link.getScreenCode()).isEqualTo("M-08");
        assertThat(link.getLinkStatus()).isEqualTo("ACTIVE");
        assertThat(link.getLinkedBy()).isEqualTo("maker1");
        assertThat(link.getLinkedAt()).isEqualTo(now);
        assertThat(link.getCancelledAt()).isNull();
    }

    @Test
    void SRS_G_38_aCircularAppliesToAPayrollTypeAndItsOfficesWithOrWithoutDescendants() {
        CircularOrderScope type = CircularOrderScope.payrollType(9L, "EMPLOYEE", "CDA");
        assertThat(type.getCircularOrderId()).isEqualTo(9L);
        assertThat(type.getScopeType()).isEqualTo("PAYROLL_TYPE");
        assertThat(type.getPayrollType()).isEqualTo("EMPLOYEE");
        assertThat(type.getPayPattern()).isEqualTo("CDA");
        assertThat(type.getLocationId()).isNull();
        assertThat(type.isIncludeDescendants()).isTrue();
        // A pay pattern is optional: the whole payroll type.
        assertThat(CircularOrderScope.payrollType(9L, "DPS", null).getPayPattern()).isNull();
        CircularOrderScope office = CircularOrderScope.location(9L, 300L, false);
        assertThat(office.getScopeType()).isEqualTo("LOCATION");
        assertThat(office.getLocationId()).isEqualTo(300L);
        assertThat(office.isIncludeDescendants()).isFalse();
        assertThat(CircularOrderScope.location(9L, 300L, true).isIncludeDescendants()).isTrue();
    }
}
