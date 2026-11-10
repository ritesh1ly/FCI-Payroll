package in.gov.fci.payroll.labour.business;

import java.math.BigDecimal;
import java.util.List;

/**
 * The Gang Strength check of a day (SRS-2-48 l.4403; I-01 l.4545; DDL labour_gang_strength;
 * BR-LABM-011). Pure functions: no state, no I/O.
 *
 * <p>"Each day, compare each gang's present strength, counted from that day's attendance at the
 * depot or FSD, with its Sanctioned Strength. If present strength is lower, raise a shortfall alert
 * to the Depot Manager for that date, showing the gang, the absent members and the number short."
 * The Ancillary Pool has no strength and is left out ("except the Ancillary Pool").
 */
public final class GangStrengthRules {

    /** The X-05 code of a gang-strength shortfall (OD-90). */
    public static final String SHORTFALL = "GANG_SHORTFALL";

    /** One member of the gang on the day, with its mark (null when unmarked). */
    public record Member(String labourCode, String code, BigDecimal presentWeight) {}

    /**
     * The day's check: the Sanctioned Strength in force, the present strength, the number short and
     * the absent members (unmarked, or marked with a code that does not count as present).
     */
    public record Check(int sanctioned, int present, int shortBy, List<String> absent) {

        /** SRS-2-48: present strength lower than the Sanctioned Strength. */
        public boolean shortfall() {
            return shortBy > 0;
        }
    }

    private GangStrengthRules() {}

    /**
     * SRS-2-48: a member is present when the day's mark counts as present (its present-day weight
     * is above zero: W, D, K, H, S, B and the half day F; the code set's weights, SRS-3-1).
     */
    public static boolean present(Member m) {
        return m.code() != null && m.presentWeight() != null && m.presentWeight().signum() > 0;
    }

    /**
     * SRS-2-48: the check of the day, or null when the gang has no Sanctioned Strength in force
     * (the Ancillary Pool; no check).
     */
    public static Check check(Integer sanctioned, List<Member> members) {
        if (sanctioned == null) {
            return null;
        }
        int present = (int) members.stream().filter(GangStrengthRules::present).count();
        List<String> absent =
                members.stream().filter(m -> !present(m)).map(Member::labourCode).toList();
        return new Check(sanctioned, present, Math.max(0, sanctioned - present), absent);
    }

    /** The X-05 field that keys one gang's shortfall of one day (one open entry each). */
    public static String key(long gangId, java.time.LocalDate day) {
        return "gang " + gangId + " " + day;
    }

    /** The X-05 detail of a shortfall (never PAN, bank or Aadhaar: labour codes only). */
    public static String detail(String gangName, java.time.LocalDate day, Check c) {
        String absent = String.join(", ", c.absent());
        String text =
                "Gang "
                        + gangName
                        + " on "
                        + day
                        + ": present "
                        + c.present()
                        + " of Sanctioned Strength "
                        + c.sanctioned()
                        + ", short "
                        + c.shortBy()
                        + (absent.isEmpty() ? "" : "; absent: " + absent)
                        + " (SRS-2-48)";
        return text.length() > 1000 ? text.substring(0, 997) + "..." : text;
    }
}
