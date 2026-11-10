package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Wave D review 5 (US-GAP-298; SRS-LV-15, BR-LEAVE-007): V077_11 carries the M-20 parameter
 * LWP_COMPONENTS over to the M-07 attribute once. Every code a confirmed version still in force
 * names (separated by '+' or ',', blanks ignored) is marked subject to LWP; a code it does not
 * name, or that is only a prefix of a named one, is left unmarked; the version is end-dated (never
 * before its own start) so M-20 shows it superseded; a second run changes nothing; an unconfirmed
 * version is neither read nor ended. Pay codes 961-965 are used by this class alone.
 */
class LwpComponentsCarryOverIT extends ApiTestSupport {

    @Autowired private DataSource dataSource;

    private void component(int code) {
        jdbc.update(
                "INSERT INTO pay_component (legacy_pay_code, component_name, component_type,"
                        + " payslip_group, source_code, taxable_flag, in_cpf_base_flag,"
                        + " effective_from) VALUES (?, ?, 'EARNING', 'PAY', 'CALC', 'Y', 'N', ?)",
                code,
                "IT LWP carry-over " + code,
                java.sql.Date.valueOf(LocalDate.of(2026, 4, 1)));
    }

    private void parameter(LocalDate from, String codes, String confirmed) {
        jdbc.update(
                "INSERT INTO leave_pay_parameter (parameter_code, parameter_name, text_value,"
                        + " version_no, effective_from, is_confirmed) VALUES ('LWP_COMPONENTS',"
                        + " 'IT components', ?, 9, ?, ?)",
                codes,
                java.sql.Date.valueOf(from),
                confirmed);
    }

    private void migrate() throws Exception {
        String script =
                Files.readString(
                        Path.of(
                                        "..",
                                        "db",
                                        "migration",
                                        "common",
                                        "V077_11__lwp_components_to_m07.sql")
                                .toAbsolutePath()
                                .normalize(),
                        StandardCharsets.UTF_8);
        try (Connection c = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(
                    c, new ByteArrayResource(script.getBytes(StandardCharsets.UTF_8)));
        }
    }

    private List<String> flags() {
        return jdbc.queryForList(
                "SELECT lwp_subject_flag FROM pay_component WHERE legacy_pay_code BETWEEN 961 AND"
                        + " 965 ORDER BY legacy_pay_code",
                String.class);
    }

    private Object end(String confirmed) {
        return jdbc.queryForObject(
                "SELECT effective_to FROM leave_pay_parameter WHERE parameter_code ="
                        + " 'LWP_COMPONENTS' AND parameter_name = 'IT components' AND is_confirmed"
                        + " = ?",
                Object.class,
                confirmed);
    }

    @Test
    void BR_LEAVE_007_theConfirmedM20ListIsCarriedOverToM07OnceAndEndDated() throws Exception {
        for (int code = 961; code <= 965; code++) {
            component(code);
        }
        LocalDate from = LocalDate.of(2099, 1, 1);
        // 96 is a prefix of 961..965 and must not mark them; 965 is named only by the draft.
        parameter(from, "961 + 962,963+96", "Y");
        parameter(from.plusDays(1), "965", "N");

        migrate();

        assertThat(flags()).containsExactly("Y", "Y", "Y", "N", "N");
        // Starts in the future: ended on its own start day, never before it.
        assertThat(end("Y").toString()).startsWith("2099-01-01");
        assertThat(end("N")).as("an unconfirmed version is left as it is").isNull();
        // The seeded demo list (V046_3_9) is closed too: no LWP_COMPONENTS version is in force.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM leave_pay_parameter WHERE parameter_code ="
                                    + " 'LWP_COMPONENTS' AND is_confirmed = 'Y' AND effective_to IS"
                                    + " NULL",
                                Long.class))
                .isZero();

        // A second run finds nothing in force and changes nothing.
        jdbc.update("UPDATE pay_component SET lwp_subject_flag = 'N' WHERE legacy_pay_code = 961");
        migrate();
        assertThat(flags()).containsExactly("N", "Y", "Y", "N", "N");
    }
}
