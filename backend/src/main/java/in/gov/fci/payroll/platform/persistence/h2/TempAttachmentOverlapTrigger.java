package in.gov.fci.payroll.platform.persistence.h2;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.h2.tools.TriggerAdapter;

/**
 * H2 port of {@code trg_labour_temp_attach_overlap} (target DDL v4.0 Section 4; SRS-2-47): a labour
 * is attached to one gang a day: attachments never overlap. Same code and message as Oracle.
 */
public class TempAttachmentOverlapTrigger extends TriggerAdapter {

    private static final String QUERY =
            "SELECT COUNT(*) FROM labour_temp_attachment a JOIN labour_temp_attachment b ON"
                + " a.employee_id = b.employee_id AND a.temp_attachment_id < b.temp_attachment_id"
                + " AND a.attachment_status IN ('PENDING_APPROVAL', 'APPROVED') AND"
                + " b.attachment_status IN ('PENDING_APPROVAL', 'APPROVED') AND a.from_date <="
                + " COALESCE(b.to_date, DATE '9999-12-31') AND COALESCE(a.to_date, DATE"
                + " '9999-12-31') >= b.from_date WHERE a.employee_id = ?";

    @Override
    public void fire(Connection conn, ResultSet oldRow, ResultSet newRow) throws SQLException {
        if (newRow == null
                || !("PENDING_APPROVAL".equals(newRow.getString("ATTACHMENT_STATUS"))
                        || "APPROVED".equals(newRow.getString("ATTACHMENT_STATUS")))) {
            return;
        }
        try (PreparedStatement statement = conn.prepareStatement(QUERY)) {
            statement.setLong(1, newRow.getLong("EMPLOYEE_ID"));
            try (ResultSet result = statement.executeQuery()) {
                if (result.next() && result.getLong(1) > 0) {
                    throw new SQLException(
                            "FCIPAY-LBR-002: a labour may be attached to only one gang per day;"
                                    + " attachments cannot overlap.",
                            "45000",
                            20105);
                }
            }
        }
    }
}
