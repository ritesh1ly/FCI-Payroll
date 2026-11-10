package in.gov.fci.payroll.payroll.business;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * SRS-15-1 / BR-CYC-003, SRS-5-10 (plan 05 phase 05f, D-13): a proposed Engine Run Order is a
 * re-ordering of exactly the active steps, and no step runs before the step it depends on (labour
 * incentive after the labour wage computation; the five NWNP month-end steps in their chain).
 */
public final class RunOrderRules {

    /** One step as the order names it (DDL {@code cycle_run_order}). */
    public record Step(String code, String dependsOn) {}

    /**
     * Why the order is refused.
     *
     * @param code INCOMPLETE (a step missing, unknown or twice) or BEFORE_DEPENDENCY
     * @param step the step at fault
     * @param dependsOn the step it must follow (BEFORE_DEPENDENCY), else null
     */
    public record Defect(String code, String step, String dependsOn) {}

    private RunOrderRules() {}

    /**
     * The defect of {@code proposed} (the step codes in their new order) over {@code steps} (every
     * active step with its dependency), or empty.
     */
    public static Optional<Defect> defect(List<String> proposed, List<Step> steps) {
        Map<String, String> dependsOn = new HashMap<>();
        for (Step s : steps) {
            dependsOn.put(s.code(), s.dependsOn());
        }
        Set<String> placed = new HashSet<>();
        for (String code : proposed) {
            if (!dependsOn.containsKey(code) || !placed.add(code)) {
                return Optional.of(new Defect("INCOMPLETE", code, null));
            }
        }
        if (placed.size() != dependsOn.size()) {
            String missing =
                    dependsOn.keySet().stream()
                            .filter(c -> !placed.contains(c))
                            .sorted()
                            .findFirst()
                            .orElseThrow();
            return Optional.of(new Defect("INCOMPLETE", missing, null));
        }
        Set<String> before = new HashSet<>();
        for (String code : proposed) {
            String needs = dependsOn.get(code);
            // A dependency on a step that is not active (not in the order) holds nothing back.
            if (needs != null && dependsOn.containsKey(needs) && !before.contains(needs)) {
                return Optional.of(new Defect("BEFORE_DEPENDENCY", code, needs));
            }
            before.add(code);
        }
        return Optional.empty();
    }
}
