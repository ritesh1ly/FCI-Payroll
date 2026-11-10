package in.gov.fci.payroll.configuration.business;

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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Enterable fields of each configuration master as the screens show them: one map per version,
 * compared field by field for the audit rows of an applied change (SRS-20-2) and shown as the
 * version's values in X-04 and the History button (SRS-20-29). One definition per master, so the
 * history and the audit rows always name the same fields.
 */
public final class MasterImages {

    private MasterImages() {}

    /** A-07 setting ({@code rule_version}): its value. */
    public static Map<String, String> ruleVersion(RuleVersion v) {
        Map<String, String> image = new LinkedHashMap<>();
        image.put("scalar_value", plain(v.getScalarValue()));
        return image;
    }

    /**
     * M-08 rule version ({@code rule_version} with its {@code rule_slab_band} rows): value,
     * formula, rounding setting, linked circular, end date and the bands in order, one per line.
     */
    public static Map<String, String> ruleVersion(RuleVersion v, List<SlabBands.Band> bands) {
        Map<String, String> image = new LinkedHashMap<>();
        image.put("scalar_value", plain(v.getScalarValue()));
        image.put("formula_expression", v.getFormulaExpression());
        image.put("rounding_rule_key", v.getRoundingRuleKey());
        image.put("circular_order_id", id(v.getCircularOrderId()));
        image.put(
                "effective_to", v.getEffectiveTo() == null ? null : v.getEffectiveTo().toString());
        image.put(
                "slab_bands",
                bands.isEmpty()
                        ? null
                        : String.join(
                                "; ",
                                bands.stream()
                                        .sorted(Comparator.comparingInt(SlabBands.Band::order))
                                        .map(
                                                b ->
                                                        (b.qualifier() == null
                                                                        ? ""
                                                                        : b.qualifier() + " ")
                                                                + plain(b.from())
                                                                + "-"
                                                                + (b.to() == null
                                                                        ? ""
                                                                        : plain(b.to()))
                                                                + ": "
                                                                + plain(b.rateOrAmount())
                                                                + (b.step() == null
                                                                        ? ""
                                                                        : " step "
                                                                                + plain(b.step())))
                                        .toList()));
        return image;
    }

    /** M-23 other parameter. */
    public static Map<String, String> otherParameter(OtherParameter p) {
        Map<String, String> image = new LinkedHashMap<>();
        image.put("parameter_name", p.getParameterName());
        image.put("numeric_value", plain(p.getNumericValue()));
        image.put("ceiling_value", plain(p.getCeilingValue()));
        image.put("percent_value", plain(p.getPercentValue()));
        image.put("window_from_time", p.getWindowFromTime());
        image.put("window_to_time", p.getWindowToTime());
        image.put(
                "instalment_count",
                p.getInstalmentCount() == null ? null : String.valueOf(p.getInstalmentCount()));
        image.put("payroll_type_scope", p.getPayrollTypeScope());
        image.put("is_confirmed", p.isIsConfirmed() ? "Y" : "N");
        return image;
    }

    /** M-24 GL mapping of a pay code. */
    public static Map<String, String> glMapping(GlAccountMapping m) {
        Map<String, String> image = new LinkedHashMap<>();
        image.put("natural_account", m.getNaturalAccount());
        image.put("cost_centre_id", id(m.getCostCentreId()));
        image.put("gl_code_combination_id", id(m.getGlCodeCombinationId()));
        return image;
    }

    /**
     * M-04 / M-22 rate set: its header fields and one entry per rate line, keyed by what the line
     * applies to (designation, category, pay pattern, basic slab, city class, rate code).
     */
    public static Map<String, String> rateSet(RateSet set, List<RateSetLine> lines) {
        Map<String, String> image = new LinkedHashMap<>();
        image.put("rate_set_name", set.getRateSetName());
        image.put("payroll_type", set.getPayrollType());
        image.put("location_id", id(set.getLocationId()));
        image.put("circular_order_id", id(set.getCircularOrderId()));
        lines.stream()
                .sorted(Comparator.comparing(MasterImages::lineKey))
                .forEach(
                        l ->
                                image.put(
                                        lineKey(l),
                                        plain(l.getRateValue())
                                                + (l.getUnitOfMeasure() == null
                                                        ? ""
                                                        : " " + l.getUnitOfMeasure())));
        return image;
    }

    /** M-16 reference value (kept as one row, versioned in place). */
    public static Map<String, String> referenceValue(
            String labelEn, Integer sortOrder, LocalDate effectiveTo, String labelHi) {
        Map<String, String> image = new LinkedHashMap<>();
        image.put("value_label_en", labelEn);
        image.put("value_label_hi", labelHi);
        image.put("sort_order", sortOrder == null ? null : String.valueOf(sortOrder));
        image.put("effective_to", effectiveTo == null ? null : effectiveTo.toString());
        return image;
    }

    /** M-14 net-pay floor of a payroll type. */
    public static Map<String, String> netPayFloor(NetPayFloorRule r) {
        Map<String, String> image = new LinkedHashMap<>();
        image.put("floor_basis", r.getFloorBasis());
        image.put("floor_value", plain(r.getFloorValue()));
        image.put("deduction_ceiling_pct", plain(r.getDeductionCeilingPct()));
        image.put("source_instrument", r.getSourceInstrument());
        image.put("is_confirmed", r.isIsConfirmed() ? "Y" : "N");
        return image;
    }

    /** M-15 ceiling norm of a claim type. */
    public static Map<String, String> ceilingNorm(AllowanceCeilingNorm n) {
        Map<String, String> image = new LinkedHashMap<>();
        image.put("ceiling_basis", n.getCeilingBasis());
        image.put("ceiling_value", plain(n.getCeilingValue()));
        image.put("periodicity", n.getPeriodicity());
        image.put("eligibility_category", n.getEligibilityCategory());
        image.put("member_scope", n.getMemberScope());
        image.put("is_confirmed", n.isIsConfirmed() ? "Y" : "N");
        return image;
    }

    /** M-27 operation. */
    public static Map<String, String> operation(OperationMaster o) {
        Map<String, String> image = new LinkedHashMap<>();
        image.put("operation_name", o.getOperationName());
        image.put("applies_dept_labour", yn(o.isAppliesDeptLabour()));
        image.put("applies_dps_labour", yn(o.isAppliesDpsLabour()));
        image.put("applies_nwnp_labour", yn(o.isAppliesNwnpLabour()));
        image.put("is_bag_based", yn(o.isIsBagBased()));
        image.put("norm_type", o.getNormType());
        image.put("rate_norm_link", o.getRateNormLink());
        image.put("is_dusting", yn(o.isIsDusting()));
        image.put("allowance_component_id", id(o.getAllowanceComponentId()));
        image.put("is_present_but_no_work", yn(o.isIsPresentButNoWork()));
        image.put("circular_order_id", id(o.getCircularOrderId()));
        return image;
    }

    /** M-25 circular (its particulars and status). */
    public static Map<String, String> circular(CircularOrder c) {
        Map<String, String> image = new LinkedHashMap<>();
        image.put("circular_number", c.getCircularNumber());
        image.put("circular_date", day(c.getCircularDate()));
        image.put("issuing_authority_code", c.getIssuingAuthorityCode());
        image.put("subject", c.getSubject());
        image.put("category_code", c.getCategoryCode());
        image.put("applies_all_offices", yn(c.isAppliesAllOffices()));
        image.put("effective_from", day(c.getEffectiveFrom()));
        image.put("effective_to", day(c.getEffectiveTo()));
        image.put("supersedes_circular_id", id(c.getSupersedesCircularId()));
        image.put("document_attachment_id", id(c.getDocumentAttachmentId()));
        image.put("circular_status", c.getCircularStatus());
        return image;
    }

    /** M-26 label version. */
    public static Map<String, String> label(LabelMaster l) {
        Map<String, String> image = new LinkedHashMap<>();
        image.put("label_text", l.getLabelText());
        image.put("is_localisable", yn(l.isIsLocalisable()));
        image.put("effective_to", day(l.getEffectiveTo()));
        image.put("label_status", l.getLabelStatus());
        return image;
    }

    private static String yn(boolean value) {
        return value ? "Y" : "N";
    }

    private static String day(LocalDate value) {
        return value == null ? null : value.toString();
    }

    static String lineKey(RateSetLine l) {
        return "rate["
                + nz(id(l.getDesignationId()))
                + "|"
                + nz(l.getCategoryCode())
                + "|"
                + nz(l.getPayPattern())
                + "|"
                + nz(plain(l.getBasicSlabFrom()))
                + "-"
                + nz(plain(l.getBasicSlabTo()))
                + "|"
                + nz(l.getCityClass())
                + "|"
                + nz(l.getRateCode())
                + "]";
    }

    /** A decimal as written ("12.5", not "12.5000"); null stays null. */
    static String plain(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private static String id(Long id) {
        return id == null ? null : String.valueOf(id);
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
