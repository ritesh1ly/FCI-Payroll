package in.gov.fci.payroll.cpf.business;

import in.gov.fci.payroll.cpf.business.model.EmployeePfHeader.MemberStatus;
import java.util.Optional;

/**
 * How a member's CPF ledger follows an approved life-cycle event (SRS-6-9, SRS-6-11, BR-CPF-016):
 * separation or dismissal makes the member an ex-member whose interest stops after the configured
 * years, unless on deputation; reinstatement clears the stop; deputation is a distinct state, not
 * an exclusion, and never carries the ex-member stop ({@code chk_pf_hdr_dep_no_stop}). A ledger
 * already finally settled or closed by a parent-zone change is never moved by an event.
 */
public final class MemberTransitions {

    /** What happens to the ex-member interest stop. */
    public enum Stop {
        SET,
        CLEAR,
        KEEP
    }

    /** The ledger's next state. */
    public record Transition(MemberStatus status, boolean deputation, Stop stop) {}

    private MemberTransitions() {}

    /** The state after {@code eventType}; empty when the event does not concern the ledger. */
    public static Optional<Transition> after(
            String eventType, MemberStatus current, boolean deputation) {
        if (current == MemberStatus.FINALLY_SETTLED || current == MemberStatus.CLOSED_ZONE_CHANGE) {
            return Optional.empty();
        }
        return switch (eventType == null ? "" : eventType) {
            case "SEPARATION", "DISMISSAL" ->
                    Optional.of(
                            new Transition(
                                    MemberStatus.EX_MEMBER,
                                    deputation,
                                    deputation ? Stop.KEEP : Stop.SET));
            case "REINSTATEMENT" ->
                    Optional.of(
                            new Transition(
                                    deputation ? MemberStatus.DEPUTATION : MemberStatus.ACTIVE,
                                    deputation,
                                    Stop.CLEAR));
            case "DEPUTATION_OUT" ->
                    Optional.of(new Transition(MemberStatus.DEPUTATION, true, Stop.CLEAR));
            case "DEPUTATION_IN" ->
                    Optional.of(new Transition(MemberStatus.ACTIVE, false, Stop.KEEP));
            default -> Optional.empty();
        };
    }
}
