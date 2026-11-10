package in.gov.fci.payroll.labour.business;

import in.gov.fci.payroll.platform.security.InputPatterns;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Labour reinstatement and nominee / dependent rules (plan 22 phase 22c; NWNP Reinstate Details and
 * Nominee &amp; Dependent Details use cases, D22-7, D22-8; DDL {@code pf_nominee}, {@code
 * employee_dependent}). Pure functions: no state, no I/O.
 */
public final class LabourRecordRules {

    private static final Pattern PRINTABLE = Pattern.compile("[\\x20-\\x7E]+");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** A person named on the record: a nominee carries its share, a dependent none. */
    public record Person(
            String name,
            String relationship,
            LocalDate birthDate,
            BigDecimal share,
            boolean nominee) {}

    private LabourRecordRules() {}

    /**
     * D22-7: only a separated member is reinstated (a retirement is final), from a date after the
     * last day of service; answers STATUS, DATE or REASON.
     */
    public static Optional<String> reinstatementDefect(
            String labourStatus, LocalDate lastDay, LocalDate date, String reason) {
        if (!"SEPARATED".equals(labourStatus)) {
            return Optional.of("STATUS");
        }
        if (date == null || (lastDay != null && !date.isAfter(lastDay))) {
            return Optional.of("DATE");
        }
        if (!multiLine(reason, 500)) {
            return Optional.of("REASON");
        }
        return Optional.empty();
    }

    /**
     * D22-8: the nominees and dependents of a member: someone named; each name 1 to 150 printable
     * characters, the relationship from the list, no date of birth after today; each nominee's
     * share above 0 and at most 100 with two decimals, the nominees' shares adding to 100 (DDL
     * pf_nominee: a set rule the service holds). Answers EMPTY, NAME, RELATIONSHIP, BIRTH, SHARE,
     * SHARE_TOTAL or REASON.
     */
    public static Optional<String> nominationDefect(
            List<Person> nominees,
            List<Person> dependents,
            Set<String> relationships,
            LocalDate today,
            String reason) {
        List<Person> n = nominees == null ? List.of() : nominees;
        List<Person> d = dependents == null ? List.of() : dependents;
        if (n.isEmpty() && d.isEmpty()) {
            return Optional.of("EMPTY");
        }
        BigDecimal total = BigDecimal.ZERO;
        for (Person p : n) {
            Optional<String> defect = personDefect(p, relationships, today);
            if (defect.isPresent()) {
                return defect;
            }
            BigDecimal s = p.share();
            if (s == null
                    || s.signum() <= 0
                    || s.compareTo(HUNDRED) > 0
                    || s.stripTrailingZeros().scale() > 2) {
                return Optional.of("SHARE");
            }
            total = total.add(s);
        }
        if (!n.isEmpty() && total.compareTo(HUNDRED) != 0) {
            return Optional.of("SHARE_TOTAL");
        }
        for (Person p : d) {
            Optional<String> defect = personDefect(p, relationships, today);
            if (defect.isPresent()) {
                return defect;
            }
        }
        if (!multiLine(reason, 500)) {
            return Optional.of("REASON");
        }
        return Optional.empty();
    }

    private static Optional<String> personDefect(
            Person p, Set<String> relationships, LocalDate today) {
        if (p == null || !text(p.name(), 150)) {
            return Optional.of("NAME");
        }
        if (p.relationship() == null || !relationships.contains(p.relationship())) {
            return Optional.of("RELATIONSHIP");
        }
        if (p.birthDate() != null && p.birthDate().isAfter(today)) {
            return Optional.of("BIRTH");
        }
        return Optional.empty();
    }

    private static boolean text(String v, int max) {
        if (v == null) {
            return false;
        }
        String s = v.strip();
        return !s.isEmpty() && s.length() <= max && PRINTABLE.matcher(s).matches();
    }

    /** As {@link #text}, for a reason entered in a text area: line breaks kept (R-ASCII). */
    private static boolean multiLine(String v, int max) {
        if (v == null) {
            return false;
        }
        String s = v.strip();
        return !s.isEmpty() && s.length() <= max && InputPatterns.isPrintableText(s);
    }
}
