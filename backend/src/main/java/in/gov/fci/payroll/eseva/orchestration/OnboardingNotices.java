package in.gov.fci.payroll.eseva.orchestration;

import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.employee.api.EmployeeEvents;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.maintenance.Housekeeping;
import in.gov.fci.payroll.workflow.api.ApproverDirectory;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * In-app notices of onboarding to Establishment / HR (plan 16 D16-36, D16-37; SRS-1-12; UC Onboard
 * New Employee/Staff Record): a record the Payroll Checker returned is told with the reason to the
 * Establishment / HR holders of its paying unit; a record in Payroll Setup Pending longer than A-07
 * ONBOARDING_AGEING_DAYS is told to them once (housekeeping on the batch instance; nothing while
 * the setting is unseeded). The escalation level waits for FCI (PENDING 16-onboard).
 */
@Component
@Loggable
public class OnboardingNotices implements Housekeeping {

    static final String RETURNED = "ONBOARDING_RETURNED";
    static final String OVERDUE = "ONBOARDING_OVERDUE";
    static final String HR = "ESTABLISHMENT_HR";

    private final JdbcTemplate jdbc;
    private final ApproverDirectory directory;
    private final SystemParameters parameters;
    private final StaffNotices notices;
    private final OrgDirectory org;

    public OnboardingNotices(
            JdbcTemplate jdbc,
            ApproverDirectory directory,
            SystemParameters parameters,
            StaffNotices notices,
            OrgDirectory org) {
        this.jdbc = jdbc;
        this.org = org;
        this.directory = directory;
        this.parameters = parameters;
        this.notices = notices;
    }

    /** SRS-1-12: Establishment / HR learns of the return and its reason. */
    @EventListener
    @Transactional
    public void on(EmployeeEvents.OnboardingReturned event) {
        notices.notify(
                directory.holders(HR, event.payingUnitId()),
                RETURNED,
                "employee",
                event.eis(),
                "New record "
                        + event.eis()
                        + " returned by "
                        + event.decidedBy()
                        + ": "
                        + (event.reason() == null ? "" : event.reason().strip()));
    }

    @Override
    public String name() {
        return "onboardingAgeingAlerts";
    }

    /** Records in Payroll Setup Pending past ONBOARDING_AGEING_DAYS, told once to HR. */
    @Override
    public int run(Instant now) {
        OptionalLong days = parameters.configured(SystemParameters.Code.ONBOARDING_AGEING_DAYS);
        if (days.isEmpty()) {
            return 0;
        }
        List<Map<String, Object>> waiting =
                jdbc.queryForList(
                        "SELECT e.emp_num, e.location_id FROM employee e WHERE e.record_status ="
                            + " 'PAYROLL_SETUP_PENDING' AND e.emp_lbr_flag = 'N' AND e.created_at <"
                            + " ? AND NOT EXISTS (SELECT 1 FROM notification n WHERE"
                            + " n.notification_type = ? AND n.related_entity_name = 'employee' AND"
                            + " n.related_entity_key = e.emp_num)",
                        Timestamp.from(now.minusSeconds(days.getAsLong() * 86_400)),
                        OVERDUE);
        int sent = 0;
        for (Map<String, Object> r : waiting) {
            Long unit =
                    org.cycleUnitOf(((Number) r.get("LOCATION_ID")).longValue())
                            .map(OrgLocation::getId)
                            .orElse(null);
            if (unit == null) {
                continue;
            }
            String eis = (String) r.get("EMP_NUM");
            sent +=
                    notices.notify(
                            directory.holders(HR, unit),
                            OVERDUE,
                            "employee",
                            eis,
                            "New record "
                                    + eis
                                    + " has waited more than "
                                    + days.getAsLong()
                                    + " days in Payroll Setup Pending.");
        }
        return sent;
    }
}
