package in.gov.fci.payroll.payroll.batch.arrear;

import java.sql.Date;
import java.util.ArrayList;
import java.util.List;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Writes the differentials of a chunk of members to {@code arrear_detail}, in the chunk's
 * transaction.
 */
public class ArrearDetailWriter implements ItemWriter<DaArrearProcessor.MemberArrear> {

    private static final String SQL =
            "INSERT INTO arrear_detail (arrear_event_id, employee_id, component_id,"
                    + " differential_yymm, original_amount, recomputed_amount, arrear_amount,"
                    + " arrear_status, period_date) VALUES (?, ?, ?, ?, ?, ?, ?, 'CALCULATED', ?)";

    private final JdbcTemplate jdbc;

    public ArrearDetailWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void write(Chunk<? extends DaArrearProcessor.MemberArrear> chunk) {
        List<Object[]> rows = new ArrayList<>();
        for (DaArrearProcessor.MemberArrear member : chunk.getItems()) {
            for (DaArrearProcessor.Detail d : member.details()) {
                rows.add(
                        new Object[] {
                            member.eventId(),
                            member.employeeId(),
                            d.componentId(),
                            d.yymm(),
                            d.original(),
                            d.recomputed(),
                            d.arrear(),
                            Date.valueOf(d.periodDate())
                        });
            }
        }
        if (!rows.isEmpty()) {
            jdbc.batchUpdate(SQL, rows);
        }
    }
}
