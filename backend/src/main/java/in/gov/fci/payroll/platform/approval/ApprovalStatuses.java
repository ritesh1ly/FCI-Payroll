package in.gov.fci.payroll.platform.approval;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The status of approval-queue items, for modules whose rows take effect only once approved
 * (implemented by the workflow module).
 */
public interface ApprovalStatuses {

    /** The status of each of {@code approvalIds} that exists. */
    Map<Long, ApprovalStatus> of(Collection<Long> approvalIds);

    /**
     * Who decided each of {@code approvalIds} that has been decided (FRS §24.9: a rule version
     * records its approver); an undecided or unknown item is absent.
     */
    Map<Long, String> deciders(Collection<Long> approvalIds);

    /** One item still awaiting its decision: the change a module proposed and who proposed it. */
    record Pending(
            Long approvalId,
            String entityKey,
            String payload,
            String submittedBy,
            Instant submittedAt) {}

    /**
     * The items of {@code entityName} awaiting a decision, newest first: for a module whose
     * proposed change lives only in the approval item (it writes nothing until approval).
     */
    List<Pending> pending(String entityName);

    /**
     * The items of one record ({@code entityName}, {@code entityKey}) awaiting a decision, newest
     * first: for a module that must know whether its record waits for a Checker (and who submitted
     * it) without reading the workflow's {@code approval_queue}.
     */
    List<Pending> pending(String entityName, String entityKey);

    /** Those of {@code approvalIds} that are approved. */
    default Set<Long> approved(Collection<Long> approvalIds) {
        return of(approvalIds).entrySet().stream()
                .filter(e -> e.getValue() == ApprovalStatus.APPROVED)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }
}
