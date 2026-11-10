package in.gov.fci.payroll.configuration.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.business.model.AllowanceCeilingNorm;
import in.gov.fci.payroll.configuration.business.model.CircularOrder;
import in.gov.fci.payroll.configuration.business.model.GlAccountMapping;
import in.gov.fci.payroll.configuration.business.model.LabelMaster;
import in.gov.fci.payroll.configuration.business.model.NetPayFloorRule;
import in.gov.fci.payroll.configuration.business.model.OperationMaster;
import in.gov.fci.payroll.configuration.business.model.OtherParameter;
import in.gov.fci.payroll.configuration.business.model.RateSet;
import in.gov.fci.payroll.configuration.business.model.RateSetLine;
import in.gov.fci.payroll.configuration.business.model.RuleVersion;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** SRS-20-2 / SRS-20-29: the fields a configuration master's audit rows and history name. */
class MasterImagesTest {

    @Test
    void SRS_20_2_decimalsAreWrittenAsEnteredAndAnEmptyValueStaysEmpty() {
        assertThat(MasterImages.plain(new BigDecimal("12.5000"))).isEqualTo("12.5");
        assertThat(MasterImages.plain(new BigDecimal("100.00"))).isEqualTo("100");
        assertThat(MasterImages.plain(new BigDecimal("0.0000"))).isEqualTo("0");
        assertThat(MasterImages.plain(null)).isNull();
    }

    // SC-01-0067: a reference value ends on its last day, never deleted (no endpoint deletes:
    // ApiSurfaceSecurityIT R_DEL).
    @Test
    void SRS_20_29_aReferenceValueNamesItsEnglishAndHindiLabelsOrderAndLastDay() {
        Map<String, String> image =
                MasterImages.referenceValue(
                        "Zonal Office", 10, LocalDate.of(2027, 3, 31), "आंचलिक");
        assertThat(image)
                .containsExactly(
                        Map.entry("value_label_en", "Zonal Office"),
                        Map.entry("value_label_hi", "आंचलिक"),
                        Map.entry("sort_order", "10"),
                        Map.entry("effective_to", "2027-03-31"));
        Map<String, String> open = MasterImages.referenceValue("Zonal Office", null, null, null);
        assertThat(open.get("sort_order")).isNull();
        assertThat(open.get("effective_to")).isNull();
        assertThat(open.get("value_label_hi")).isNull();
    }

    // ---------------------------------------------------------------- every master's image

    private static BigDecimal n(String v) {
        return new BigDecimal(v);
    }

    @Test
    void SRS_20_2_anA07SettingNamesItsValueOnly() {
        RuleVersion v = mock(RuleVersion.class);
        when(v.getScalarValue()).thenReturn(n("30.00"));
        assertThat(MasterImages.ruleVersion(v)).containsExactly(Map.entry("scalar_value", "30"));
    }

    @Test
    void SRS_20_2_anOtherParameterNamesEveryValueAndItsConfirmation() {
        OtherParameter p = mock(OtherParameter.class);
        when(p.getParameterName()).thenReturn("Bonus ceiling");
        when(p.getNumericValue()).thenReturn(n("7000.00"));
        when(p.getCeilingValue()).thenReturn(n("21000"));
        when(p.getPercentValue()).thenReturn(n("8.33"));
        when(p.getWindowFromTime()).thenReturn("09:00");
        when(p.getWindowToTime()).thenReturn("18:00");
        when(p.getInstalmentCount()).thenReturn(12);
        when(p.getPayrollTypeScope()).thenReturn("STAFF");
        when(p.isIsConfirmed()).thenReturn(true);
        assertThat(MasterImages.otherParameter(p))
                .containsExactly(
                        Map.entry("parameter_name", "Bonus ceiling"),
                        Map.entry("numeric_value", "7000"),
                        Map.entry("ceiling_value", "21000"),
                        Map.entry("percent_value", "8.33"),
                        Map.entry("window_from_time", "09:00"),
                        Map.entry("window_to_time", "18:00"),
                        Map.entry("instalment_count", "12"),
                        Map.entry("payroll_type_scope", "STAFF"),
                        Map.entry("is_confirmed", "Y"));
        // Mockito answers 0 for a wrapper getter: an absent value is stubbed as null explicitly.
        OtherParameter empty = mock(OtherParameter.class);
        when(empty.getInstalmentCount()).thenReturn(null);
        Map<String, String> none = MasterImages.otherParameter(empty);
        assertThat(none.get("instalment_count")).isNull();
        assertThat(none.get("numeric_value")).isNull();
        assertThat(none.get("is_confirmed")).isEqualTo("N");
    }

    @Test
    void SRS_20_2_aGlMappingNamesItsAccountAndIds() {
        GlAccountMapping m = mock(GlAccountMapping.class);
        when(m.getNaturalAccount()).thenReturn("510101");
        when(m.getCostCentreId()).thenReturn(4L);
        when(m.getGlCodeCombinationId()).thenReturn(99L);
        assertThat(MasterImages.glMapping(m))
                .containsExactly(
                        Map.entry("natural_account", "510101"),
                        Map.entry("cost_centre_id", "4"),
                        Map.entry("gl_code_combination_id", "99"));
        GlAccountMapping unmapped = mock(GlAccountMapping.class);
        when(unmapped.getCostCentreId()).thenReturn(null);
        when(unmapped.getGlCodeCombinationId()).thenReturn(null);
        Map<String, String> none = MasterImages.glMapping(unmapped);
        assertThat(none.get("cost_centre_id")).isNull();
        assertThat(none.get("gl_code_combination_id")).isNull();
    }

    @Test
    void SRS_20_2_aRateSetNamesItsHeaderAndOneEntryPerLineInKeyOrder() {
        RateSet set = mock(RateSet.class);
        when(set.getRateSetName()).thenReturn("DA rates");
        when(set.getPayrollType()).thenReturn("STAFF");
        when(set.getLocationId()).thenReturn(1L);
        when(set.getCircularOrderId()).thenReturn(42L);
        RateSetLine percent = mock(RateSetLine.class);
        when(percent.getDesignationId()).thenReturn(null);
        when(percent.getRateCode()).thenReturn("DA");
        when(percent.getRateValue()).thenReturn(n("53.00"));
        when(percent.getUnitOfMeasure()).thenReturn("PCT");
        RateSetLine slab = mock(RateSetLine.class);
        when(slab.getDesignationId()).thenReturn(7L);
        when(slab.getCategoryCode()).thenReturn("CAT1");
        when(slab.getPayPattern()).thenReturn("IDA");
        when(slab.getBasicSlabFrom()).thenReturn(n("0"));
        when(slab.getBasicSlabTo()).thenReturn(n("25000.00"));
        when(slab.getCityClass()).thenReturn("X");
        when(slab.getRateCode()).thenReturn("HRA");
        when(slab.getRateValue()).thenReturn(n("27"));
        Map<String, String> image = MasterImages.rateSet(set, List.of(slab, percent));
        assertThat(image)
                .containsExactly(
                        Map.entry("rate_set_name", "DA rates"),
                        Map.entry("payroll_type", "STAFF"),
                        Map.entry("location_id", "1"),
                        Map.entry("circular_order_id", "42"),
                        Map.entry("rate[7|CAT1|IDA|0-25000|X|HRA]", "27"),
                        Map.entry("rate[|||-||DA]", "53 PCT"));
        assertThat(MasterImages.rateSet(set, List.of())).hasSize(4);
    }

    @Test
    void SRS_20_2_aNetPayFloorNamesItsBasisValuesSourceAndConfirmation() {
        NetPayFloorRule r = mock(NetPayFloorRule.class);
        when(r.getFloorBasis()).thenReturn("PERCENT_OF_GROSS");
        when(r.getFloorValue()).thenReturn(n("25.0"));
        when(r.getDeductionCeilingPct()).thenReturn(n("50"));
        when(r.getSourceInstrument()).thenReturn("Order 7");
        when(r.isIsConfirmed()).thenReturn(true);
        assertThat(MasterImages.netPayFloor(r))
                .containsExactly(
                        Map.entry("floor_basis", "PERCENT_OF_GROSS"),
                        Map.entry("floor_value", "25"),
                        Map.entry("deduction_ceiling_pct", "50"),
                        Map.entry("source_instrument", "Order 7"),
                        Map.entry("is_confirmed", "Y"));
        assertThat(MasterImages.netPayFloor(mock(NetPayFloorRule.class)).get("is_confirmed"))
                .isEqualTo("N");
    }

    @Test
    void SRS_20_2_aCeilingNormNamesItsBasisValuePeriodicityScopeAndConfirmation() {
        AllowanceCeilingNorm c = mock(AllowanceCeilingNorm.class);
        when(c.getCeilingBasis()).thenReturn("FIXED");
        when(c.getCeilingValue()).thenReturn(n("1500.00"));
        when(c.getPeriodicity()).thenReturn("MONTHLY");
        when(c.getEligibilityCategory()).thenReturn("DEMO_ALL");
        when(c.getMemberScope()).thenReturn("BOTH");
        when(c.isIsConfirmed()).thenReturn(true);
        assertThat(MasterImages.ceilingNorm(c))
                .containsExactly(
                        Map.entry("ceiling_basis", "FIXED"),
                        Map.entry("ceiling_value", "1500"),
                        Map.entry("periodicity", "MONTHLY"),
                        Map.entry("eligibility_category", "DEMO_ALL"),
                        Map.entry("member_scope", "BOTH"),
                        Map.entry("is_confirmed", "Y"));
        assertThat(MasterImages.ceilingNorm(mock(AllowanceCeilingNorm.class)).get("is_confirmed"))
                .isEqualTo("N");
    }

    @Test
    void SRS_20_2_anOperationNamesEveryFlagAsYOrN() {
        OperationMaster o = mock(OperationMaster.class);
        when(o.getOperationName()).thenReturn("Wagon to Platform");
        when(o.isAppliesDeptLabour()).thenReturn(true);
        when(o.isAppliesDpsLabour()).thenReturn(false);
        when(o.isAppliesNwnpLabour()).thenReturn(true);
        when(o.isIsBagBased()).thenReturn(true);
        when(o.getNormType()).thenReturn("BAG");
        when(o.getRateNormLink()).thenReturn("NORM_SLAB");
        when(o.isIsDusting()).thenReturn(false);
        when(o.getAllowanceComponentId()).thenReturn(348L);
        when(o.isIsPresentButNoWork()).thenReturn(true);
        when(o.getCircularOrderId()).thenReturn(42L);
        assertThat(MasterImages.operation(o))
                .containsExactly(
                        Map.entry("operation_name", "Wagon to Platform"),
                        Map.entry("applies_dept_labour", "Y"),
                        Map.entry("applies_dps_labour", "N"),
                        Map.entry("applies_nwnp_labour", "Y"),
                        Map.entry("is_bag_based", "Y"),
                        Map.entry("norm_type", "BAG"),
                        Map.entry("rate_norm_link", "NORM_SLAB"),
                        Map.entry("is_dusting", "N"),
                        Map.entry("allowance_component_id", "348"),
                        Map.entry("is_present_but_no_work", "Y"),
                        Map.entry("circular_order_id", "42"));
    }

    @Test
    void SRS_20_2_aCircularNamesItsParticularsDatesLinksAndStatus() {
        CircularOrder c = mock(CircularOrder.class);
        when(c.getCircularNumber()).thenReturn("HQ/DA/2026/3");
        when(c.getCircularDate()).thenReturn(LocalDate.of(2026, 10, 1));
        when(c.getIssuingAuthorityCode()).thenReturn("HQ_PERSONNEL");
        when(c.getSubject()).thenReturn("DA revision");
        when(c.getCategoryCode()).thenReturn("DA");
        when(c.isAppliesAllOffices()).thenReturn(true);
        when(c.getEffectiveFrom()).thenReturn(LocalDate.of(2026, 7, 1));
        when(c.getEffectiveTo()).thenReturn(null);
        when(c.getSupersedesCircularId()).thenReturn(41L);
        when(c.getDocumentAttachmentId()).thenReturn(9L);
        when(c.getCircularStatus()).thenReturn("ACTIVE");
        Map<String, String> expected = new java.util.LinkedHashMap<>();
        expected.put("circular_number", "HQ/DA/2026/3");
        expected.put("circular_date", "2026-10-01");
        expected.put("issuing_authority_code", "HQ_PERSONNEL");
        expected.put("subject", "DA revision");
        expected.put("category_code", "DA");
        expected.put("applies_all_offices", "Y");
        expected.put("effective_from", "2026-07-01");
        expected.put("effective_to", null);
        expected.put("supersedes_circular_id", "41");
        expected.put("document_attachment_id", "9");
        expected.put("circular_status", "ACTIVE");
        assertThat(MasterImages.circular(c)).containsExactlyEntriesOf(expected);
    }

    @Test
    void SRS_20_2_aLabelNamesItsTextLocalisationEndAndStatus() {
        LabelMaster l = mock(LabelMaster.class);
        when(l.getLabelText()).thenReturn("Sardar");
        when(l.isIsLocalisable()).thenReturn(true);
        when(l.getEffectiveTo()).thenReturn(LocalDate.of(2027, 3, 31));
        when(l.getLabelStatus()).thenReturn("ACTIVE");
        assertThat(MasterImages.label(l))
                .containsExactly(
                        Map.entry("label_text", "Sardar"),
                        Map.entry("is_localisable", "Y"),
                        Map.entry("effective_to", "2027-03-31"),
                        Map.entry("label_status", "ACTIVE"));
        assertThat(MasterImages.label(mock(LabelMaster.class)).get("is_localisable"))
                .isEqualTo("N");
    }
}
