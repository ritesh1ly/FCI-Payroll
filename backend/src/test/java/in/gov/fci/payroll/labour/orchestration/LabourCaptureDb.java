package in.gov.fci.payroll.labour.orchestration;

import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * An in-memory copy (H2, Oracle mode) of the columns of the DDL v4.0 tables that the I-01 and I-02
 * reads use (labour_gang, org_location, employee, labour_profile, labour_gang_membership,
 * attendance_code_master, labour_attendance, attendance_punch_exception, approval_queue, work_slip,
 * work_slip_row, work_slip_participant, operation_master, bag_type, labour_overtime,
 * labour_leave_application, labour_leave_type, attendance_source_master, biometric_enrolment,
 * biometric_punch), without their foreign keys, so the SQL of {@link AttendanceViews} and {@link
 * WorkSlipViews} runs for real in a unit test. One database per instance (test classes run in
 * parallel).
 */
final class LabourCaptureDb implements AutoCloseable {

    final JdbcTemplate jdbc;
    final NamedParameterJdbcTemplate named;

    LabourCaptureDb() {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:labour" + UUID.randomUUID() + ";MODE=Oracle;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(h2);
        named = new NamedParameterJdbcTemplate(jdbc);
        for (String ddl : DDL) {
            jdbc.execute(ddl);
        }
    }

    private static final String[] DDL = {
        "CREATE TABLE org_location (location_id NUMBER(19) PRIMARY KEY, location_code VARCHAR2(20),"
                + " location_name VARCHAR2(100), parent_location_id NUMBER(19))",
        "CREATE TABLE labour_gang (gang_id NUMBER(19) PRIMARY KEY, gang_code VARCHAR2(20),"
                + " gang_name VARCHAR2(100), depot_location_id NUMBER(19), payroll_type"
                + " VARCHAR2(20), gang_type VARCHAR2(20), effective_from DATE, effective_to DATE)",
        "CREATE TABLE employee (employee_id NUMBER(19) PRIMARY KEY, first_name VARCHAR2(50),"
                + " last_name VARCHAR2(50), retirement_date DATE)",
        "CREATE TABLE labour_profile (labour_profile_id NUMBER(19) PRIMARY KEY, employee_id"
                + " NUMBER(19), labour_number VARCHAR2(20), depot_location_id NUMBER(19))",
        "CREATE TABLE labour_gang_membership (gang_id NUMBER(19), employee_id NUMBER(19),"
                + " effective_from DATE, effective_to DATE, gang_role VARCHAR2(30))",
        "CREATE TABLE attendance_code_master (attendance_code_id NUMBER(19) PRIMARY KEY,"
                + " attendance_code VARCHAR2(10), code_meaning VARCHAR2(100), engine_code"
                + " VARCHAR2(20), effective_from DATE, effective_to DATE, present_day_weight"
                + " NUMBER(4,2), is_lwp CHAR(1), counts_toward_present CHAR(1))",
        "CREATE TABLE labour_attendance (employee_id NUMBER(19), gang_id NUMBER(19),"
                + " attendance_date DATE, attendance_code VARCHAR2(10), attendance_code_id"
                + " NUMBER(19), capture_mode VARCHAR2(20), depot_confirmed_flag CHAR(1),"
                + " work_slip_filed_flag CHAR(1), depot_location_id NUMBER(19))",
        "CREATE TABLE attendance_punch_exception (employee_id NUMBER(19), attendance_date DATE,"
                + " exception_status VARCHAR2(10), exception_type VARCHAR2(30))",
        "CREATE TABLE approval_queue (approval_id NUMBER(19) PRIMARY KEY, entity_name"
                + " VARCHAR2(60), entity_key VARCHAR2(60), status VARCHAR2(20), decided_by"
                + " VARCHAR2(50), decided_at TIMESTAMP)",
        "CREATE TABLE work_slip (work_slip_id NUMBER(19) PRIMARY KEY, work_slip_number"
                + " VARCHAR2(30), gang_id NUMBER(19), work_slip_date DATE, slip_status"
                + " VARCHAR2(20), source_code VARCHAR2(10), engine_code VARCHAR2(20),"
                + " continue_work_flag CHAR(1), rejection_reason VARCHAR2(200), created_by"
                + " VARCHAR2(50), approval_id NUMBER(19), duplicate_of_slip_id NUMBER(19))",
        "CREATE TABLE work_slip_row (slip_row_id NUMBER(19) PRIMARY KEY, work_slip_id NUMBER(19),"
                + " row_order NUMBER(4), operation_id NUMBER(19), bag_type_id NUMBER(19),"
                + " number_of_bags NUMBER(10), start_time VARCHAR2(5), end_time VARCHAR2(5),"
                + " norm_type VARCHAR2(20), pbnw_reason VARCHAR2(200))",
        "CREATE TABLE work_slip_participant (work_slip_id NUMBER(19), employee_id NUMBER(19))",
        "CREATE TABLE operation_master (operation_id NUMBER(19) PRIMARY KEY, operation_code"
                + " VARCHAR2(20), operation_name VARCHAR2(100), is_present_but_no_work CHAR(1))",
        "CREATE TABLE bag_type (bag_type_id NUMBER(19) PRIMARY KEY, bag_type_name VARCHAR2(50))",
        "CREATE TABLE labour_overtime (overtime_id NUMBER(19) PRIMARY KEY, gang_id NUMBER(19),"
                + " employee_id NUMBER(19), operation_id NUMBER(19), start_at TIMESTAMP, end_at"
                + " TIMESTAMP, normal_rate_hours NUMBER(6,2), double_rate_hours NUMBER(6,2),"
                + " bags_in_ot_hours NUMBER(10), min_bags_met CHAR(1), is_ancillary CHAR(1),"
                + " ot_date DATE, period_date DATE, ot_status VARCHAR2(20))",
        "CREATE TABLE labour_leave_type (labour_leave_type_id NUMBER(19) PRIMARY KEY,"
                + " leave_type_code VARCHAR2(10), payroll_type VARCHAR2(20))",
        "CREATE TABLE labour_leave_application (labour_leave_app_id NUMBER(19) PRIMARY KEY,"
                + " labour_leave_type_id NUMBER(19), labour_profile_id NUMBER(19), employee_id"
                + " NUMBER(19), converted_from_app_id NUMBER(19), entry_origin VARCHAR2(20),"
                + " leave_from DATE, leave_to DATE, is_back_dated CHAR(1), created_at TIMESTAMP,"
                + " application_status VARCHAR2(20))",
        "CREATE TABLE attendance_source_master (location_id NUMBER(19), attendance_mode"
                + " VARCHAR2(20), anna_darpan_flag CHAR(1), source_status VARCHAR2(20),"
                + " effective_from DATE, effective_to DATE, fallback_to_manual CHAR(1))",
        "CREATE TABLE biometric_enrolment (employee_id NUMBER(19), location_id NUMBER(19),"
                + " effective_from DATE, effective_to DATE)",
        "CREATE TABLE biometric_punch (employee_id NUMBER(19), punch_direction VARCHAR2(3),"
                + " punch_at TIMESTAMP, period_date DATE)",
    };

    /** Runs one INSERT (or any statement) with positional values. */
    void sql(String statement, Object... values) {
        jdbc.update(statement, values);
    }

    static java.sql.Date d(String iso) {
        return java.sql.Date.valueOf(iso);
    }

    static java.sql.Timestamp ts(String iso) {
        return java.sql.Timestamp.valueOf(iso);
    }

    /**
     * The offices, gangs, labour and memberships both views read. The session's depot is D03
     * (location 3); D05, D06 and D07 report to it; D04 is another region's depot. Gang G03 sits at
     * D04 (out of scope), G04 is DPS, G05 is not a handling gang, G11 and G12 start in November,
     * G13 ended in September.
     */
    void seedBase() {
        sql("INSERT INTO org_location VALUES (2, 'RO02', 'Region Two', NULL)");
        sql("INSERT INTO org_location VALUES (3, 'D03', 'Depot Three', 2)");
        sql("INSERT INTO org_location VALUES (4, 'D04', 'Depot Four', 9)");
        sql("INSERT INTO org_location VALUES (5, 'D05', 'Depot Five', 3)");
        sql("INSERT INTO org_location VALUES (6, 'D06', 'Depot Six', 3)");
        sql("INSERT INTO org_location VALUES (7, 'D07', 'Depot Seven', 3)");
        gang(10, "G01", "Gang One", 3, "DEPT_LABOUR", "HANDLING", "2026-01-01", null);
        gang(11, "G02", "Gang Two", 5, "DEPT_LABOUR", "ANCILLARY_POOL", "2026-01-01", null);
        gang(12, "G03", "Gang Far", 4, "DEPT_LABOUR", "HANDLING", "2026-01-01", null);
        gang(13, "G04", "Gang Dps", 3, "DPS_LABOUR", "HANDLING", "2026-01-01", null);
        gang(14, "G05", "Gang Other", 3, "DEPT_LABOUR", "SUPERVISORY", "2026-01-01", null);
        gang(15, "G06", "Gang Retired", 3, "DEPT_LABOUR", "HANDLING", "2026-01-01", null);
        gang(16, "G07", "Gang Punch", 3, "DEPT_LABOUR", "HANDLING", "2026-01-01", null);
        gang(17, "G08", "Gang Gap", 3, "DEPT_LABOUR", "HANDLING", "2026-01-01", null);
        gang(18, "G09", "Gang Over", 3, "DEPT_LABOUR", "HANDLING", "2026-01-01", null);
        gang(19, "G10", "Gang Blank", 3, "DEPT_LABOUR", "HANDLING", "2026-01-01", null);
        gang(20, "G11", "Gang Six", 6, "DEPT_LABOUR", "HANDLING", "2026-11-01", null);
        gang(21, "G12", "Gang Seven", 7, "DEPT_LABOUR", "HANDLING", "2026-11-01", null);
        gang(22, "G13", "Gang Ended", 3, "DEPT_LABOUR", "HANDLING", "2026-01-01", "2026-09-30");
        labour(100, "Asha", "Devi", null, 3);
        labour(102, "Ravi", null, null, 5);
        labour(103, "Mohan", "Lal", "2026-10-29", 3);
        labour(104, "Gita", "Rani", null, 3);
        labour(105, "Hari", "Om", null, 3);
        labour(106, "Far", "Away", null, 4);
        labour(107, "Uma", "Shankar", null, 3);
        labour(108, "Blank", "Gang", null, 3);
        labour(109, "Sunil", "Das", null, 3);
        labour(110, "Kamla", "Bai", "2026-11-01", 3);
        labour(111, "Ramu", "Kaka", null, 5);
        labour(112, "Seema", "Six", null, 6);
        labour(113, "Sohan", "Seven", null, 7);
        member(10, 100, "2026-10-30", "MATE");
        member(11, 102, "2026-10-31", "LABOUR");
        member(15, 103, "2026-10-30", "LABOUR");
        member(16, 104, "2026-10-31", "LABOUR");
        member(17, 105, "2026-10-30", "LABOUR");
        member(12, 106, "2026-10-01", "LABOUR");
        member(19, 108, "2026-10-01", "LABOUR");
        member(10, 109, "2026-11-01", "LABOUR");
        member(10, 110, "2026-11-01", "LABOUR");
        member(11, 111, "2026-11-01", "LABOUR");
        member(20, 112, "2026-11-01", "LABOUR");
        member(21, 113, "2026-11-01", "LABOUR");
    }

    void gang(
            long id,
            String code,
            String name,
            long depot,
            String type,
            String gangType,
            String from,
            String to) {
        sql(
                "INSERT INTO labour_gang VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id,
                code,
                name,
                depot,
                type,
                gangType,
                d(from),
                to == null ? null : d(to));
    }

    void labour(long id, String first, String last, String retires, long depot) {
        sql(
                "INSERT INTO employee VALUES (?, ?, ?, ?)",
                id,
                first,
                last,
                retires == null ? null : d(retires));
        sql("INSERT INTO labour_profile VALUES (?, ?, ?, ?)", id + 1000, id, "DL0" + id, depot);
    }

    void member(long gang, long employee, String from, String role) {
        sql(
                "INSERT INTO labour_gang_membership VALUES (?, ?, ?, NULL, ?)",
                gang,
                employee,
                d(from),
                role);
    }

    @Override
    public void close() {
        jdbc.execute("SHUTDOWN");
    }
}
