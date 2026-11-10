package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.audit.api.RecordHistorySource;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * SRS-G-40 (screens, menus and messages in English and Hindi): every role, every screen and every
 * menu section the database holds has its label in both bundles. The role bar and the menus show
 * {@code role.<code>} and {@code screen.<code>}; a role or screen added by a migration without them
 * would show the raw key (found when the Module 07 CPF roles reached the role bar unlabelled).
 */
class LabelCoverageIT extends ApiTestSupport {

    @Autowired private List<RecordHistorySource> sources;

    private static Properties bundle(String locale) throws IOException {
        Properties p = new Properties();
        try (InputStream in =
                LabelCoverageIT.class.getResourceAsStream(
                        "/i18n/messages_" + locale + ".properties")) {
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }

    @Test
    void SRS_G_40_everyRoleAndScreenHasItsEnglishAndHindiLabel() throws IOException {
        List<String> keys = new ArrayList<>();
        // The roles a migration ships. A role created on A-02 (audited under app_role, e.g. by
        // AdministrationIT in the same database) shows its role_name, not a bundle label.
        jdbc.queryForList(
                        "SELECT role_code FROM app_role r WHERE NOT EXISTS (SELECT 1 FROM"
                                + " audit_event a WHERE a.entity_name = 'app_role' AND"
                                + " a.entity_key = r.role_code AND a.field_name = 'CREATE')",
                        String.class)
                .forEach(c -> keys.add("role." + c));
        jdbc.queryForList("SELECT screen_code FROM app_screen", String.class)
                .forEach(c -> keys.add("screen." + c));
        // Every record type X-04 offers (one RecordHistorySource per master; found 2026-10-24 by
        // the
        // E2E review: six types added by Modules 04 and 07 showed their raw keys).
        sources.forEach(src -> keys.add("x04.entity." + src.entity()));
        // The unit of each A-07 setting (SystemParameters shows "a07.unit.<unit>"; found 2026-10-24
        // by the role-screen matrix E2E: the page-size settings showed the raw key a07.unit.rows).
        jdbc.queryForList(
                        "SELECT DISTINCT unit_label FROM rule_setting_bound WHERE unit_label IS NOT"
                                + " NULL",
                        String.class)
                .forEach(u -> keys.add("a07.unit." + u));
        // The menu section of each screen's module (MenuCatalog: menu.section.<module as an id>).
        jdbc.queryForList("SELECT DISTINCT module_name FROM app_screen", String.class)
                .forEach(
                        m ->
                                keys.add(
                                        "menu.section."
                                                + m.toLowerCase(Locale.ROOT)
                                                        .replaceAll("[^a-z0-9]+", "_")));
        // The name of each A-07 setting (SystemParameters shows "a07.param.<code>"; found
        // 2026-10-26 by the role-screen matrix E2E: four settings of modules 05 and 16 showed raw
        // keys).
        for (in.gov.fci.payroll.configuration.api.SystemParameters.Code code :
                in.gov.fci.payroll.configuration.api.SystemParameters.Code.values()) {
            keys.add("a07.param." + code.name());
        }
        for (String locale : List.of("en", "hi")) {
            Properties labels = bundle(locale);
            assertThat(keys.stream().filter(k -> !labels.containsKey(k)).toList())
                    .as(locale)
                    .isEmpty();
        }
    }
}
