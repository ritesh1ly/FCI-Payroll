package in.gov.fci.payroll.workflow.orchestration;

import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.platform.security.OtpGatedAction;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * X-08 "Actions requiring OTP - the complete go-live list (18)" as Wireframes v6.1 draws it
 * (OD-121; FRS V0.5 §6.4.2.5, §6.13.9; SRS-G-21): every {@code otp_challenge.gated_action} in the
 * order of {@link OtpGatedAction}, with the screens on which it is performed as the pack draws them
 * ({@link OtpGatedAction#screens()}), the screens whose active approval route asks for it ({@code
 * approval_workflow_action.otp_action_code}) and the roles that enter it there (the levels of those
 * routes that ask for the OTP: {@code otp_at_level}, or every level of a route whose action
 * requires it). The routes are read from the configuration, never a list in code; the sign-in and
 * own-password codes are entered by every user and have no route. Login always asks for the OTP
 * (SRS-G-21, SRS line 905; owner kept it 2026-11-06, OD-126).
 */
@Service
@Loggable
public class OtpCatalogueService {

    /** The X-08 screen (every role holds its VIEW, V078_14). */
    public static final String SCREEN = "X-08";

    /**
     * One gated action: its code, the drawn screens, the screens of its configured routes and the
     * roles that enter the OTP there.
     */
    public record GatedAction(
            String code, List<String> screens, List<String> routedScreens, List<String> roles) {}

    private final JdbcTemplate jdbc;
    private final Authorization policy;

    public OtpCatalogueService(JdbcTemplate jdbc, Authorization policy) {
        this.jdbc = jdbc;
        this.policy = policy;
    }

    @Transactional(readOnly = true)
    public List<GatedAction> catalogue(AccessContext context) {
        policy.require(context, SCREEN, "VIEW");
        Map<String, Set<String>> screens = new LinkedHashMap<>();
        Map<String, Set<String>> roles = new LinkedHashMap<>();
        jdbc.query(
                "SELECT a.otp_action_code, a.screen_code, l.role_code FROM approval_workflow_action"
                    + " a LEFT JOIN approval_workflow_level l ON l.workflow_action_id ="
                    + " a.workflow_action_id AND (l.otp_at_level = 'Y' OR a.otp_required = 'Y')"
                    + " WHERE a.otp_action_code IS NOT NULL AND a.action_status = 'ACTIVE' ORDER BY"
                    + " a.screen_code, l.level_no",
                rs -> {
                    String code = rs.getString(1);
                    screens.computeIfAbsent(code, k -> new LinkedHashSet<>()).add(rs.getString(2));
                    String role = rs.getString(3);
                    if (role != null) {
                        roles.computeIfAbsent(code, k -> new LinkedHashSet<>()).add(role);
                    }
                });
        List<GatedAction> out = new ArrayList<>();
        for (OtpGatedAction action : OtpGatedAction.values()) {
            String code = action.name();
            out.add(
                    new GatedAction(
                            code,
                            action.screens(),
                            List.copyOf(screens.getOrDefault(code, Set.of())),
                            List.copyOf(roles.getOrDefault(code, Set.of()))));
        }
        return out;
    }
}
