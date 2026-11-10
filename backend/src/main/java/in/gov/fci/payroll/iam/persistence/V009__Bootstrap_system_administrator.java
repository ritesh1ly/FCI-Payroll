package in.gov.fci.payroll.iam.persistence;

import in.gov.fci.payroll.iam.business.PasswordPolicy;
import in.gov.fci.payroll.platform.config.ApiProperties;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * First-deploy System Administrator (RBAC_DESIGN.md §9, "break glass"). Runs once; does nothing if
 * any user already holds an approved System Administrator assignment (the demo seed has one).
 * Otherwise creates the global System Administrator group and one account whose password comes from
 * {@code fci.api.bootstrap.admin-initial-password}, is hashed here (never in SQL) and must be
 * changed at first sign-in. This is the only access granted without a human checker; the approval
 * row records the deployment as maker and checker so the trail shows exactly that.
 */
@Component
public class V009__Bootstrap_system_administrator extends BaseJavaMigration {

    static final String GROUP_CODE = "AG-SYSADM-GLOBAL";

    private final PasswordEncoder passwordEncoder;
    private final ApiProperties.Bootstrap bootstrap;

    public V009__Bootstrap_system_administrator(
            PasswordEncoder passwordEncoder, ApiProperties properties) {
        this.passwordEncoder = passwordEncoder;
        this.bootstrap = properties.bootstrap();
    }

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        if (count(
                        connection,
                        "SELECT COUNT(*) FROM app_user_access_group a JOIN access_group g ON"
                            + " g.access_group_id = a.access_group_id JOIN app_role r ON r.role_id"
                            + " = g.role_id JOIN approval_queue q ON q.approval_id = a.approval_id"
                            + " WHERE r.role_code = 'SYSTEM_ADMINISTRATOR' AND q.status ="
                            + " 'APPROVED' AND a.revoked_at IS NULL")
                > 0) {
            return;
        }
        String password = bootstrap.adminInitialPassword();
        if (password == null
                || password.isBlank()
                || password.length() < PasswordPolicy.MIN_LENGTH) {
            throw new IllegalStateException(
                    "fci.api.bootstrap.admin-initial-password (at least "
                            + PasswordPolicy.MIN_LENGTH
                            + " characters) is required for the first deployment");
        }
        String login = bootstrap.sysadminLoginId().strip().toLowerCase(Locale.ROOT);
        Timestamp now = Timestamp.from(Instant.now());
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        update(
                connection,
                "INSERT INTO approval_queue (module_code, entity_name, entity_key, action_type,"
                    + " payload, submitted_by, submitted_at, status, decided_by, decided_at,"
                    + " decision_reason) VALUES ('A-04', 'BOOTSTRAP', ?, 'CREATE',"
                    + " '{\"bootstrap\":\"sysadmin\"}', 'deploy.bootstrap', ?, 'APPROVED',"
                    + " 'deploy.release', ?, 'First-deploy System Administrator (break glass)')",
                login,
                now,
                now);
        long approvalId =
                id(
                        connection,
                        "SELECT MAX(approval_id) FROM approval_queue WHERE entity_name ="
                                + " 'BOOTSTRAP' AND entity_key = ?",
                        login);

        if (count(connection, "SELECT COUNT(*) FROM access_group WHERE group_code = ?", GROUP_CODE)
                == 0) {
            update(
                    connection,
                    "INSERT INTO access_group (group_code, group_name, role_id, scope_type,"
                            + " consolidated_view, effective_from, approval_id) SELECT ?, 'System"
                            + " Administrator - Global', role_id, 'GLOBAL', 'N', ?, ? FROM app_role"
                            + " WHERE role_code = 'SYSTEM_ADMINISTRATOR'",
                    GROUP_CODE,
                    java.sql.Date.valueOf(today),
                    approvalId);
        }
        long groupId =
                id(
                        connection,
                        "SELECT MIN(access_group_id) FROM access_group WHERE group_code = ?",
                        GROUP_CODE);

        if (count(connection, "SELECT COUNT(*) FROM app_user WHERE login_id = ?", login) == 0) {
            update(
                    connection,
                    "INSERT INTO app_user (login_id, display_name, user_category, registered_email,"
                        + " user_status, row_version) VALUES (?, 'System Administrator', 'SYSTEM',"
                        + " ?, 'ACTIVE', 0)",
                    login,
                    login);
        }
        long userId = id(connection, "SELECT user_id FROM app_user WHERE login_id = ?", login);
        if (count(connection, "SELECT COUNT(*) FROM app_user_credential WHERE user_id = ?", userId)
                == 0) {
            // Spring Security 7: encode() may return null; the bootstrap never stores a missing
            // hash.
            String hash =
                    java.util.Objects.requireNonNull(
                            passwordEncoder.encode(PasswordPolicy.normalise(password)),
                            "the password encoder returned no hash");
            update(
                    connection,
                    "INSERT INTO app_user_credential (user_id, password_hash, hash_algorithm,"
                            + " password_changed_at, must_change_flag, failed_attempt_count,"
                            + " row_version) VALUES (?, ?, ?, ?, 'Y', 0, 0)",
                    userId,
                    hash,
                    hash.startsWith("{argon2}") ? "ARGON2ID" : "BCRYPT",
                    now);
            update(
                    connection,
                    "INSERT INTO app_user_preference (user_id, preferred_locale, updated_at) VALUES"
                            + " (?, 'en', ?)",
                    userId,
                    now);
        }
        update(
                connection,
                "INSERT INTO app_user_access_group (user_id, access_group_id, effective_from,"
                        + " assigned_by, approval_id) VALUES (?, ?, ?, 'deploy.bootstrap', ?)",
                userId,
                groupId,
                java.sql.Date.valueOf(today),
                approvalId);
        update(
                connection,
                "INSERT INTO audit_event (entity_name, entity_key, field_name, old_value,"
                        + " new_value, changed_by, changed_at, effective_date, reason, approval_id,"
                        + " change_type, module_code) VALUES ('app_user_access_group', ?, 'CREATE',"
                        + " NULL, 'APPROVED', 'deploy.bootstrap', ?, ?, 'First-deploy System"
                        + " Administrator', ?, 'INITIAL_VALUE', 'A-04')",
                login + "|" + GROUP_CODE,
                now,
                java.sql.Date.valueOf(today),
                approvalId);
    }

    private static void update(Connection connection, String sql, Object... args)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, args);
            statement.executeUpdate();
        }
    }

    private static long count(Connection connection, String sql, Object... args)
            throws SQLException {
        return id(connection, sql, args);
    }

    private static long id(Connection connection, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, args);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("Bootstrap query returned no row");
                }
                return rows.getLong(1);
            }
        }
    }

    private static void bind(PreparedStatement statement, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            statement.setObject(i + 1, args[i]);
        }
    }
}
