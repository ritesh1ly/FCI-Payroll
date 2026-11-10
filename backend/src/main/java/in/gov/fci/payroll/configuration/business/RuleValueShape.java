package in.gov.fci.payroll.configuration.business;

import java.util.Map;
import java.util.Optional;

/**
 * What a rule version's value is, by rule type (SRS-19-1; target DDL v4.0 {@code rule_version}
 * columns): the one place that maps the seven SRS rule types to the value the engine evaluates.
 * Submission checks ({@link RuleProposals}), the engine and the M-08 form all dispatch on the
 * shape, so a new rule type is one entry in {@link #BY_TYPE}, never a new string comparison.
 *
 * <p>Types without a shape are not maintained on M-08: ROUNDING is a rule setting named by a
 * version ({@code rounding_rule_key}), STATUTORY_OUTPUT and REPORT are definitions of the report
 * engine, SETTING belongs to A-07.
 */
public enum RuleValueShape {
    /** One number ({@code scalar_value}): Rate, Ceiling, Floor. */
    SCALAR(Part.NUMBER),
    /** Contiguous, non-overlapping bands ({@code rule_slab_band}, SRS-19-5): Slab. */
    SLAB(Part.BANDS),
    /** A formula of the bounded grammar that yields a number (SRS-19-15): Formula. */
    NUMBER_FORMULA(Part.FORMULA),
    /** A formula that yields a condition: Eligibility, Applicability. */
    CONDITION_FORMULA(Part.FORMULA);

    /** The one part of a {@code rule_version} a value of the shape is held in. */
    public enum Part {
        /** {@code scalar_value}. */
        NUMBER("one value"),
        /** {@code rule_slab_band} rows. */
        BANDS("its bands"),
        /** {@code formula_expression}. */
        FORMULA("a formula");

        private final String described;

        Part(String described) {
            this.described = described;
        }

        /** As a refusal names it: "this rule takes one value and nothing else". */
        public String described() {
            return described;
        }
    }

    private final Part part;

    RuleValueShape(Part part) {
        this.part = part;
    }

    /** The part the value is held in: a version carries that part and no other. */
    public Part part() {
        return part;
    }

    private static final Map<String, RuleValueShape> BY_TYPE =
            Map.of(
                    "RATE", SCALAR,
                    "CEILING", SCALAR,
                    "FLOOR", SCALAR,
                    "SLAB", SLAB,
                    "FORMULA", NUMBER_FORMULA,
                    "ELIGIBILITY", CONDITION_FORMULA,
                    "APPLICABILITY", CONDITION_FORMULA);

    /** The shape of a rule type M-08 maintains; empty for any other type. */
    public static Optional<RuleValueShape> of(String typeCode) {
        return Optional.ofNullable(typeCode == null ? null : BY_TYPE.get(typeCode));
    }

    /** A formula shape: its text is checked against the grammar in force. */
    public boolean isFormula() {
        return part == Part.FORMULA;
    }
}
