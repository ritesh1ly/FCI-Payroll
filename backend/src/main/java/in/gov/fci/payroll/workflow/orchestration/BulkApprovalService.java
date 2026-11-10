package in.gov.fci.payroll.workflow.orchestration;

import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.workflow.business.model.ApprovalItem;
import in.gov.fci.payroll.workflow.persistence.WorkflowRepositories;
import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Bulk approval on X-02 / W-01 (SRS-G-13, UC-07 A2 "a Checker approves several items together
 * through bulk approval on the high-volume screens. Each item still follows the identity rule and,
 * where required, OTP"; US-GAP-21). Each selected item is approved through the one decision path,
 * in its own transaction, so it keeps every check of a single approval (scope, Maker not Checker,
 * level, mandatory attachment) and one refusal never undoes the others. Only items whose action
 * allows bulk approval and whose level asks for no OTP are taken; the others are refused with the
 * reason and approved on their own. Every approved item records the bulk reference.
 */
@Service
public class BulkApprovalService {

    /** The most items one bulk approval takes (a page of the inbox). */
    public static final int MAX_ITEMS = 100;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** What became of the selection. */
    public record Outcome(String reference, List<Long> approved, List<Refused> refused) {}

    /** An item not approved, with the reason (an error code or BULK_NOT_ALLOWED). */
    public record Refused(Long approvalId, String reason) {}

    private final ApprovalService approvals;
    private final WorkflowRepositories.Approvals items;
    private final TransactionTemplate each;
    private final Clock clock;

    public BulkApprovalService(
            ApprovalService approvals,
            WorkflowRepositories.Approvals items,
            TransactionTemplate each,
            Clock clock) {
        this.approvals = approvals;
        this.items = items;
        this.each = each;
        this.clock = clock;
    }

    public Outcome approve(AccessContext context, List<Long> approvalIds, String reason) {
        LinkedHashSet<Long> ids =
                new LinkedHashSet<>(approvalIds == null ? List.of() : approvalIds);
        ids.remove(null);
        if (ids.isEmpty() || ids.size() > MAX_ITEMS) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        String reference =
                "BULK-"
                        + STAMP.format(clock.instant().atZone(ZoneId.of("Asia/Kolkata")))
                        + "-"
                        + java.util.UUID.randomUUID().toString().substring(0, 8);
        List<Long> approved = new ArrayList<>();
        List<Refused> refused = new ArrayList<>();
        for (Long id : ids) {
            try {
                each.executeWithoutResult(
                        status -> {
                            ApprovalItem item =
                                    items.findById(id)
                                            .orElseThrow(
                                                    () ->
                                                            new FciException(
                                                                    CommonErrorCode.NOT_FOUND));
                            if (!approvals.bulkEligible(item)) {
                                throw new NotInBulk();
                            }
                            // Sec 6.4: the decision row is written once, with the bulk reference.
                            approvals.decide(
                                    context,
                                    id,
                                    ApprovalService.Decision.APPROVE,
                                    reason,
                                    null,
                                    reference);
                        });
                approved.add(id);
            } catch (NotInBulk notInBulk) {
                refused.add(new Refused(id, "BULK_NOT_ALLOWED"));
            } catch (FciException refusal) {
                refused.add(new Refused(id, refusal.getErrorCode().code()));
            }
        }
        return new Outcome(reference, approved, refused);
    }

    /** The item cannot be approved in bulk (its action does not allow it, or it needs an OTP). */
    private static final class NotInBulk extends RuntimeException {
        private static final long serialVersionUID = 1L;

        NotInBulk() {
            super(null, null, false, false);
        }
    }
}
