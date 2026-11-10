package in.gov.fci.payroll.payroll.business;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The pre-lock gates of a payroll cycle instance (SRS-15-11, SRS-15-14 … 18, BR-CYC-005): G0, G1,
 * G3 and G4 are hard gates nobody may override; G2, G5 and G6 may be accepted by the Checker with a
 * reason. A gate whose source holds nothing is not met: no source is ever assumed complete (plan 05
 * D-1). Pure rules over what the sources hold.
 */
public final class CycleGates {

    /** The gate codes in panel order. */
    public static final List<String> CODES = List.of("G0", "G1", "G2", "G3", "G4", "G5", "G6");

    /** SRS-15-15: no role, Administrator included, accepts these. */
    public static final Set<String> HARD = Set.of("G0", "G1", "G3", "G4");

    /**
     * SRS-15-18 G5: the statutory sub-processes that must be complete (CF-02: NPS, not pension).
     */
    public static final List<String> STATUTORY =
            List.of("CPF_PF", "INCOME_TAX", "PROFESSIONAL_TAX", "NPS_DEDUCTION");

    /** SRS-15-18 G4: the totals that must agree. */
    public static final List<String> TOTAL_SOURCES =
            List.of("PAYROLL_RESULT", "SALARY_CONTROL_REGISTER", "DISBURSEMENT_REGISTER");

    private CycleGates() {}

    /** One gate as evaluated. */
    public record Gate(String code, boolean met, String detail) {
        public boolean overridable() {
            return !HARD.contains(code);
        }
    }

    /**
     * G0 run complete: a run of record, COMPLETE, every work unit committed and the result count
     * reconciled to the eligible population.
     */
    public static Gate g0(
            Long runId, String runStatus, long units, long committed, Long results, Long eligible) {
        if (runId == null) {
            return new Gate("G0", false, "No computation run of record");
        }
        boolean met =
                "COMPLETE".equals(runStatus)
                        && units > 0
                        && committed == units
                        && results != null
                        && results.equals(eligible);
        return new Gate(
                "G0",
                met,
                "Run "
                        + runId
                        + " "
                        + runStatus
                        + ": "
                        + committed
                        + " of "
                        + units
                        + " work units committed, "
                        + results
                        + " results for "
                        + eligible
                        + " eligible");
    }

    /** G1 computation complete: every engine of the instance completed an Actual run. */
    public static Gate g1(Map<String, String> engineStates) {
        if (engineStates.isEmpty()) {
            return new Gate("G1", false, "No engine has completed an Actual run");
        }
        List<String> open =
                engineStates.entrySet().stream()
                        .filter(e -> !"COMPLETE".equals(e.getValue()))
                        .map(e -> e.getKey() + " " + e.getValue())
                        .sorted()
                        .toList();
        return new Gate(
                "G1",
                open.isEmpty(),
                open.isEmpty()
                        ? "Complete: "
                                + String.join(
                                        ", ", engineStates.keySet().stream().sorted().toList())
                        : "Not complete: " + String.join(", ", open));
    }

    /** G2 exceptions cleared: no unresolved exception and no failed Block pre-lock check. */
    public static Gate g2(long blocking, long other, long failedBlockChecks) {
        boolean met = blocking == 0 && other == 0 && failedBlockChecks == 0;
        return new Gate(
                "G2",
                met,
                blocking
                        + " blocking and "
                        + other
                        + " other unresolved exceptions; "
                        + failedBlockChecks
                        + " failed Block checks");
    }

    /** Whether an unmet G2 may be accepted: only non-blocking exceptions remain (SRS-15-16). */
    public static boolean g2Acceptable(long blocking, long failedBlockChecks) {
        return blocking == 0 && failedBlockChecks == 0;
    }

    /** G3 verification complete: nothing unactioned in the inbox for the instance. */
    public static Gate g3(long pending) {
        return new Gate("G3", pending == 0, pending + " approvals pending for the unit");
    }

    /** One control total of a source. */
    public record Total(
            String source, long members, BigDecimal gross, BigDecimal deductions, BigDecimal net) {
        boolean agrees(Total other) {
            return members == other.members
                    && gross.compareTo(other.gross) == 0
                    && deductions.compareTo(other.deductions) == 0
                    && net.compareTo(other.net) == 0;
        }
    }

    /**
     * G4 control totals reconciled: for every payroll type, the payroll result, Salary Control
     * Register and disbursement register totals are all present and agree.
     */
    public static Gate g4(Map<String, List<Total>> byPayrollType) {
        if (byPayrollType.isEmpty()) {
            return new Gate("G4", false, "No control totals");
        }
        List<String> problems = new ArrayList<>();
        byPayrollType.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(
                        e -> {
                            List<String> present =
                                    e.getValue().stream().map(Total::source).toList();
                            List<String> missing =
                                    TOTAL_SOURCES.stream()
                                            .filter(s -> !present.contains(s))
                                            .toList();
                            if (!missing.isEmpty()) {
                                problems.add(e.getKey() + ": no " + String.join(", ", missing));
                            } else {
                                Total first = e.getValue().get(0);
                                if (!e.getValue().stream().allMatch(first::agrees)) {
                                    problems.add(e.getKey() + ": totals differ");
                                }
                            }
                        });
        return new Gate(
                "G4",
                problems.isEmpty(),
                problems.isEmpty() ? "Totals agree" : String.join("; ", problems));
    }

    /** One statutory sub-process state. */
    public record Statutory(String process, String state, Long uncomputed) {}

    /** G5 statutory complete: CPF / PF, income tax, PT and NPS deduction complete for everyone. */
    public static Gate g5(List<Statutory> processes) {
        List<String> open =
                STATUTORY.stream()
                        .filter(
                                p ->
                                        processes.stream()
                                                .noneMatch(
                                                        s ->
                                                                s.process().equals(p)
                                                                        && "COMPLETE"
                                                                                .equals(s.state())
                                                                        && (s.uncomputed() == null
                                                                                || s.uncomputed()
                                                                                        == 0)))
                        .toList();
        return new Gate(
                "G5",
                open.isEmpty(),
                open.isEmpty() ? "Complete" : "Not complete: " + String.join(", ", open));
    }

    /**
     * SRS l.1524 / SRS-15-16 (BMAD TR-010): G5 is accepted only "where a named member is
     * legitimately excluded". The statutory status holds counts per process (DDL
     * cycle_statutory_status), so an acceptance is possible only when every statutory process is
     * COMPLETE and what stays uncomputed is members - at most as many as the acceptance names; a
     * process not complete is no member's exclusion, and a member left uncomputed and not named
     * keeps G5 unmet.
     *
     * @return why the acceptance cannot cover the gap (NO_MEMBER,
     *     PROCESS_NOT_COMPLETE:&lt;process&gt;, UNNAMED_MEMBERS:&lt;uncomputed&gt;), empty when it
     *     can
     */
    public static Optional<String> g5Exclusion(List<Statutory> processes, int named) {
        if (named <= 0) {
            return Optional.of("NO_MEMBER");
        }
        long uncomputed = 0;
        for (String p : STATUTORY) {
            Optional<Statutory> s =
                    processes.stream().filter(x -> x.process().equals(p)).findFirst();
            if (s.isEmpty() || !"COMPLETE".equals(s.get().state())) {
                return Optional.of("PROCESS_NOT_COMPLETE:" + p);
            }
            if (s.get().uncomputed() != null) {
                uncomputed = Math.max(uncomputed, s.get().uncomputed());
            }
        }
        return uncomputed > named ? Optional.of("UNNAMED_MEMBERS:" + uncomputed) : Optional.empty();
    }

    /**
     * G6 negative / zero net quarantined: no quarantined member left unreviewed, and a confirmed
     * net-pay floor in force for the comparison (an unconfigured floor is never assumed).
     */
    public static Gate g6(long unreviewed, boolean floorConfirmed) {
        boolean met = unreviewed == 0 && floorConfirmed;
        String detail =
                unreviewed
                        + " quarantined members not reviewed"
                        + (floorConfirmed ? "" : "; no confirmed net-pay floor in force (M-14)");
        return new Gate("G6", met, detail);
    }

    /**
     * SRS-15-11: the gates that keep the lock refused: an unmet hard gate, or an unmet overridable
     * gate without an acceptance.
     */
    public static List<String> blocking(List<Gate> gates, Set<String> accepted) {
        return gates.stream()
                .filter(g -> !g.met() && (!g.overridable() || !accepted.contains(g.code())))
                .map(Gate::code)
                .toList();
    }
}
