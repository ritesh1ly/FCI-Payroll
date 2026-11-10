package in.gov.fci.payroll.payroll.api;

import in.gov.fci.payroll.configuration.api.PayConfiguration;
import in.gov.fci.payroll.payroll.business.PayDataException;
import in.gov.fci.payroll.payroll.business.PayRules;
import in.gov.fci.payroll.platform.security.SecureTokens;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Applies the registered {@link StaffDeductions} to computed members (plan 16 phase 16e-2;
 * SRS-1-6), in their order of recovery, for the Actual run, the simulation and the off-cycle
 * pending salary alike, so all three net the same way. A caller {@link #open opens} one {@link
 * Session} per run: every source is pinned to the period's rules once. A source that cannot compute
 * refuses the member ({@link PayDataException}: the failure log, C-08), never a net without its
 * deduction. With no source registered the outcome is unchanged (net = gross).
 */
@Component
public class StaffDeductionRunner {

    private final List<StaffDeductions> sources;

    public StaffDeductionRunner(ObjectProvider<StaffDeductions> sources) {
        this.sources =
                sources.orderedStream()
                        .sorted(Comparator.comparingInt(StaffDeductions::order))
                        .toList();
    }

    /**
     * Gate G5 (SRS-15-18): the statutory sub-processes whose source takes part in every run (a
     * process without one is never reported complete, {@code CycleRegisters}).
     */
    public java.util.Set<String> statutoryProcesses() {
        java.util.Set<String> processes = new java.util.TreeSet<>();
        for (StaffDeductions source : sources) {
            source.statutoryProcess().ifPresent(processes::add);
        }
        return java.util.Set.copyOf(processes);
    }

    /** The sources pinned to the rules in force on the first day of {@code month}. */
    public Session open(YearMonth month) {
        List<StaffDeductions.Run> runs = new ArrayList<>();
        List<Integer> orders = new ArrayList<>();
        for (StaffDeductions source : sources) {
            runs.add(source.open(month.atDay(1)));
            orders.add(source.order());
        }
        return new Session(month, runs, orders);
    }

    /** One run's deductions: the pinned sources of a month. */
    public static final class Session {

        private final YearMonth month;
        private final List<StaffDeductions.Run> runs;
        private final List<Integer> orders;

        private Session(YearMonth month, List<StaffDeductions.Run> runs, List<Integer> orders) {
            this.month = month;
            this.runs = List.copyOf(runs);
            this.orders = List.copyOf(orders);
        }

        /**
         * The outcome with the month's deductions taken (a skipped outcome is returned as it is).
         */
        public PayRules.Outcome apply(
                PayRules.Outcome outcome,
                long employeeId,
                Long locationId,
                String payPattern,
                PayConfiguration.Snapshot config) {
            if (!PayRules.COMPUTED.equals(outcome.status()) || runs.isEmpty()) {
                return outcome;
            }
            StaffDeductions.Member member =
                    new StaffDeductions.Member(
                            employeeId,
                            locationId,
                            payPattern,
                            month,
                            outcome.lines().stream()
                                    .map(l -> new StaffDeductions.Earning(l.payCode(), l.amount()))
                                    .toList(),
                            outcome.gross());
            List<PayRules.Contribution> contributions = new ArrayList<>();
            List<PayRules.Deduction> deductions = new ArrayList<>();
            // D19-4: the month's pay reductions (leave) first, so every later source sees the pay
            // as reduced.
            List<PayRules.Reduction> reductions = new ArrayList<>();
            for (StaffDeductions.Run run : runs) {
                try {
                    for (StaffDeductions.Reduction r : run.reductions(member)) {
                        reductions.add(new PayRules.Reduction(r.payCode(), r.amount()));
                    }
                } catch (StaffDeductions.NotComputable gap) {
                    throw new PayDataException(gap.getMessage() + " (employee " + employeeId + ")");
                }
            }
            member = reduced(member, reductions);
            List<PayRules.Earning> earnings = new ArrayList<>();
            // D10-6: the month's one-time earnings first, so every deduction (TDS) sees them. An
            // earning that cannot be computed (an arrear whose pay code is not configured) refuses
            // this member only, as a reduction or a deduction does - never the whole run.
            for (StaffDeductions.Run run : runs) {
                try {
                    for (StaffDeductions.Earning e : run.earnings(member)) {
                        earnings.add(new PayRules.Earning(e.payCode(), e.amount()));
                        member = withEarning(member, e.payCode(), e.amount());
                    }
                } catch (StaffDeductions.NotComputable gap) {
                    throw new PayDataException(gap.getMessage() + " (employee " + employeeId + ")");
                }
            }
            for (StaffDeductions.Run run : runs) {
                try {
                    // SRS-9-21: an employer contribution is an earning for every later source
                    // (the employer NPS reaches the taxable gross of TDS).
                    for (StaffDeductions.Contribution c : run.contributions(member)) {
                        contributions.add(
                                new PayRules.Contribution(
                                        c.earningPayCode(), c.deductionPayCode(), c.amount()));
                        member = withEarning(member, c.earningPayCode(), c.amount());
                    }
                    BigDecimal taken =
                            deductions.stream()
                                    .map(PayRules.Deduction::amount)
                                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                                    .add(
                                            contributions.stream()
                                                    .map(PayRules.Contribution::amount)
                                                    .reduce(BigDecimal.ZERO, BigDecimal::add));
                    for (StaffDeductions.Line line : run.deductions(member.withDeducted(taken))) {
                        deductions.add(
                                new PayRules.Deduction(
                                        line.payCode(),
                                        line.amount(),
                                        line.rate(),
                                        line.basis(),
                                        line.exact()));
                    }
                } catch (StaffDeductions.NotComputable gap) {
                    throw new PayDataException(gap.getMessage() + " (employee " + employeeId + ")");
                }
            }
            return PayRules.withDeductions(
                    PayRules.reduced(outcome, reductions),
                    earnings,
                    contributions,
                    deductions,
                    config);
        }

        /**
         * The member with each reduced earning at its reduced amount (rounded as the engine does).
         */
        private static StaffDeductions.Member reduced(
                StaffDeductions.Member member, List<PayRules.Reduction> reductions) {
            if (reductions.isEmpty()) {
                return member;
            }
            List<StaffDeductions.Earning> earnings = new ArrayList<>();
            BigDecimal gross = member.gross();
            for (StaffDeductions.Earning e : member.earnings()) {
                BigDecimal cut = PayRules.reductionOf(e.payCode(), reductions);
                BigDecimal amount = e.amount().subtract(cut).max(BigDecimal.ZERO);
                gross = gross.subtract(e.amount().subtract(amount));
                earnings.add(new StaffDeductions.Earning(e.payCode(), amount));
            }
            return new StaffDeductions.Member(
                    member.employeeId(),
                    member.locationId(),
                    member.payPattern(),
                    member.month(),
                    earnings,
                    gross);
        }

        private static StaffDeductions.Member withEarning(
                StaffDeductions.Member member, int payCode, BigDecimal amount) {
            List<StaffDeductions.Earning> earnings = new ArrayList<>(member.earnings());
            earnings.add(new StaffDeductions.Earning(payCode, amount));
            return new StaffDeductions.Member(
                    member.employeeId(),
                    member.locationId(),
                    member.payPattern(),
                    member.month(),
                    earnings,
                    member.gross().add(amount));
        }

        /**
         * The run's rule-version-set hash: the pay configuration's and every source's versions
         * (BR-CYC-011 … 017); the configuration's own hash while no source is registered.
         */
        public String versionSetHash(PayConfiguration.Snapshot config) {
            if (runs.isEmpty()) {
                return config.versionSetHash();
            }
            StringBuilder all = new StringBuilder(config.versionSetHash());
            for (int i = 0; i < runs.size(); i++) {
                all.append('|').append(orders.get(i)).append('=').append(runs.get(i).versions());
            }
            return SecureTokens.sha256Hex(all.toString());
        }
    }
}
