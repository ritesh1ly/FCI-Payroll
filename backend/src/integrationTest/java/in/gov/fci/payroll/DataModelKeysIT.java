package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * NEW-G1-11 (US-03-05 AC2, US-03-16 AC1): the data-model key statements as the schema holds them
 * (target DDL v4.0 with ddl-auto=validate). SRS-DM-1: an entity is keyed on its stated key, never
 * on a biographical composite; SRS-DM-5: the cycle instance is keyed on period plus organisation
 * unit and every result and result line belongs to its instance; SRS-DM-6: a result line has its
 * own key, a foreign key to its pay component and a signed amount.
 */
class DataModelKeysIT extends ApiTestSupport {

    private List<String> columns(String table, String type) {
        return jdbc.queryForList(
                "SELECT k.column_name FROM information_schema.table_constraints c JOIN"
                        + " information_schema.key_column_usage k ON k.constraint_name ="
                        + " c.constraint_name AND k.table_name = c.table_name WHERE c.table_name ="
                        + " ? AND c.constraint_type = ? ORDER BY c.constraint_name,"
                        + " k.ordinal_position",
                String.class,
                table,
                type);
    }

    @Test
    void SRS_DM_1_aPersonIsKeyedOnItsIdNeverOnABiographicalComposite() {
        assertThat(columns("EMPLOYEE", "PRIMARY KEY")).containsExactly("EMPLOYEE_ID");
        assertThat(columns("EMPLOYEE", "PRIMARY KEY"))
                .doesNotContain("FIRST_NAME", "LAST_NAME", "BIRTH_DATE", "PAN_NUMBER");
    }

    @Test
    void SRS_DM_5_theCycleInstanceIsOnePerPeriodAndUnitAndOwnsItsResults() {
        assertThat(columns("PAYROLL_CYCLE_INSTANCE", "PRIMARY KEY"))
                .containsExactly("CYCLE_INSTANCE_ID");
        assertThat(columns("PAYROLL_CYCLE_INSTANCE", "UNIQUE"))
                .containsSequence("PAYROLL_YYMM", "ORG_UNIT_ID");
        assertThat(columns("PAYROLL_RESULT", "FOREIGN KEY")).contains("CYCLE_INSTANCE_ID");
        assertThat(columns("PAYROLL_RESULT_COMPONENT", "FOREIGN KEY"))
                .contains("CYCLE_INSTANCE_ID", "PAYROLL_RESULT_ID");
        // Two instances of one period and unit are refused; another unit of the period is a row
        // of its own (Region and Headquarters instances are separate).
        long unit =
                jdbc.queryForObject(
                        "SELECT location_id FROM org_location WHERE location_code = 'DH001'",
                        Long.class);
        long hq =
                jdbc.queryForObject(
                        "SELECT location_id FROM org_location WHERE location_code = 'HQ001'",
                        Long.class);
        String insert =
                "INSERT INTO payroll_cycle_instance (payroll_yymm, org_unit_id, org_unit_level,"
                        + " fin_year, cycle_status, initiated_by) VALUES ('199812', ?, ?,"
                        + " '1998-99', 'COMPUTED', 'it.fixture')";
        if (jdbc.queryForObject(
                        "SELECT COUNT(*) FROM payroll_cycle_instance WHERE payroll_yymm = '199812'"
                                + " AND org_unit_id = ?",
                        Integer.class,
                        unit)
                == 0) {
            jdbc.update(insert, unit, "DO");
        }
        if (jdbc.queryForObject(
                        "SELECT COUNT(*) FROM payroll_cycle_instance WHERE payroll_yymm = '199812'"
                                + " AND org_unit_id = ?",
                        Integer.class,
                        hq)
                == 0) {
            jdbc.update(insert, hq, "HQ");
        }
        assertThatThrownBy(() -> jdbc.update(insert, unit, "DO"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM payroll_cycle_instance WHERE payroll_yymm ="
                                        + " '199812'",
                                Integer.class))
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    void SRS_DM_6_aResultLineHasItsKeyItsPayComponentAndASignedAmount() {
        assertThat(columns("PAYROLL_RESULT_COMPONENT", "PRIMARY KEY"))
                .contains("RESULT_COMPONENT_ID");
        assertThat(columns("PAYROLL_RESULT_COMPONENT", "FOREIGN KEY")).contains("COMPONENT_ID");
        Map<String, Object> amount =
                jdbc.queryForMap(
                        "SELECT data_type, numeric_scale FROM information_schema.columns WHERE"
                                + " table_name = 'PAYROLL_RESULT_COMPONENT' AND column_name ="
                                + " 'AMOUNT'");
        assertThat(String.valueOf(amount.get("DATA_TYPE"))).isIn("NUMERIC", "DECIMAL");
        // Signed: no check keeps the amount positive (a deduction is the negative line).
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM information_schema.check_constraints c JOIN"
                                    + " information_schema.table_constraints t ON t.constraint_name"
                                    + " = c.constraint_name WHERE t.table_name ="
                                    + " 'PAYROLL_RESULT_COMPONENT' AND UPPER(c.check_clause) LIKE"
                                    + " '%AMOUNT%>%'",
                                Integer.class))
                .isZero();
    }
}
