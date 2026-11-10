package in.gov.fci.payroll.general.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.general.business.model.FallbackEnableScope;
import in.gov.fci.payroll.general.business.model.FallbackUpload;
import in.gov.fci.payroll.general.persistence.GeneralRepositories;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The Checker's decisions on X-07, applied inside the decision's transaction (a failing effect
 * rolls the decision back):
 *
 * <ul>
 *   <li>enablement (X07_CREATE) approved: the enablement and its office scope are created, OPEN;
 *       rejected or returned: nothing was ever enabled;
 *   <li>upload (X07_SUBMIT) approved with OTP: APPROVED with checker, time and OTP challenge
 *       ({@code chk_hrms_fb_upload_otp}); the enablement is APPROVED once every enabled office has
 *       an approved upload; returned / rejected: the upload records the reason ({@code
 *       chk_hrms_fb_upload_rsn}).
 * </ul>
 */
@Component
public class FallbackEffects {

    private final FallbackService service;
    private final GeneralRepositories.Enablements enablements;
    private final GeneralRepositories.EnableScopes scopes;
    private final GeneralRepositories.Uploads uploads;
    private final AuditService audit;

    public FallbackEffects(
            FallbackService service,
            GeneralRepositories.Enablements enablements,
            GeneralRepositories.EnableScopes scopes,
            GeneralRepositories.Uploads uploads,
            AuditService audit) {
        this.service = service;
        this.enablements = enablements;
        this.scopes = scopes;
        this.uploads = uploads;
        this.audit = audit;
    }

    @EventListener
    public void on(ApprovalDecided decision) {
        if (decision.concerns(FallbackService.ENABLEMENT) && decision.approved()) {
            service.openApproved(
                    decision.payload(), decision.approvalId(), decision.checkerLoginId());
        } else if (decision.concerns(FallbackService.UPLOAD)) {
            uploads.findByApprovalId(decision.approvalId())
                    .ifPresent(upload -> decide(upload, decision));
        }
    }

    private void decide(FallbackUpload upload, ApprovalDecided decision) {
        String before = upload.getStatus().name();
        if (decision.approved()) {
            upload.approve(
                    decision.checkerLoginId(), decision.otpChallengeId(), decision.decidedAt());
        } else {
            upload.decline(
                    decision.outcome() == ApprovalStatus.RETURNED
                            ? FallbackUpload.Status.RETURNED
                            : FallbackUpload.Status.REJECTED,
                    decision.checkerLoginId(),
                    decision.reason(),
                    decision.decidedAt());
        }
        uploads.save(upload);
        audit.record(
                decision.checkerLoginId(),
                AuditService.Entry.change(
                                FallbackService.UPLOAD,
                                upload.getBatchNo(),
                                "upload_status",
                                before,
                                upload.getStatus().name(),
                                decision.reason() == null ? "Approved with OTP" : decision.reason())
                        .approval(decision.approvalId())
                        .module(FallbackService.SCREEN));
        if (decision.approved()) {
            completeEnablement(upload.getEnablementId());
        }
    }

    /** The enablement is APPROVED when every office it names holds an approved upload. */
    private void completeEnablement(Long enablementId) {
        Set<Long> approvedOffices =
                uploads.findByEnablementId(enablementId).stream()
                        .filter(u -> u.getStatus() == FallbackUpload.Status.APPROVED)
                        .map(FallbackUpload::getOrgUnitId)
                        .collect(Collectors.toSet());
        List<Long> enabled =
                scopes.findByEnablementId(enablementId).stream()
                        .map(FallbackEnableScope::getOrgUnitId)
                        .toList();
        if (approvedOffices.containsAll(enabled)) {
            enablements
                    .findById(enablementId)
                    .ifPresent(
                            e -> {
                                e.approved();
                                enablements.save(e);
                            });
        }
    }
}
