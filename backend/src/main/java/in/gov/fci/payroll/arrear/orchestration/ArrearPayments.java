package in.gov.fci.payroll.arrear.orchestration;

import in.gov.fci.payroll.arrear.business.ArrearRules;
import in.gov.fci.payroll.configuration.api.NetPayFloors;
import in.gov.fci.payroll.payroll.api.StaffDeductions;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Approved arrears in the staff payroll (plan 13 phase 13a; SRS-8-8, SRS-8-17, SRS-8-21; D13-1,
 * D13-2): the member's differentials of APPROVED events posted to this month or carried from an
 * earlier one are paid as earnings under the components' arrear pay codes (M-16 ARREAR_PAY_CODE),
 * and a net negative is recovered under the arrear recovery code within the net-pay floor (M-14),
 * last of the recoveries. A component without an arrear pay code, or a recovery without the floor
 * or the recovery code, refuses the member: never a default (R-RULE).
 */
@Component
public class ArrearPayments implements StaffDeductions {

    /** After every other recovery: the floor is applied to what is left. */
    static final int ORDER = 90;

    static final String RECOVERY = "RECOVERY";

    private final JdbcTemplate jdbc;
    private final NetPayFloors floors;

    public ArrearPayments(JdbcTemplate jdbc, NetPayFloors floors) {
        this.jdbc = jdbc;
        this.floors = floors;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public Run open(LocalDate asOf) {
        Map<String, Integer> codes = new TreeMap<>();
        jdbc.query(
                "SELECT v.value_code, v.numeric_attribute FROM reference_value v JOIN"
                    + " reference_list l ON l.list_id = v.list_id WHERE l.list_code ="
                    + " 'ARREAR_PAY_CODE' AND v.numeric_attribute IS NOT NULL AND v.effective_from"
                    + " <= ? AND (v.effective_to IS NULL OR v.effective_to >= ?)",
                rs -> {
                    codes.put(rs.getString(1), rs.getBigDecimal(2).intValueExact());
                },
                Date.valueOf(asOf),
                Date.valueOf(asOf));
        Optional<NetPayFloors.Floor> floor = floors.inForce("STAFF", asOf);
        return new ArrearRun(codes, floor);
    }

    private final class ArrearRun implements Run {

        private final Map<String, Integer> codes;
        private final Optional<NetPayFloors.Floor> floor;
        private final AtomicReference<Object[]> last = new AtomicReference<>();

        ArrearRun(Map<String, Integer> codes, Optional<NetPayFloors.Floor> floor) {
            this.codes = codes;
            this.floor = floor;
        }

        @Override
        public String versions() {
            return "arrears="
                    + codes
                    + ";floor="
                    + floor.map(f -> f.basis() + ":" + f.value()).orElse("-");
        }

        @Override
        public List<Earning> earnings(Member member) {
            ArrearRules.Split split = split(member);
            List<Earning> out = new ArrayList<>();
            for (Map.Entry<Integer, BigDecimal> e : split.earnings().entrySet()) {
                Integer code = codes.get(String.valueOf(e.getKey()));
                if (code == null) {
                    throw new NotComputable(
                            "Arrear pay code of component "
                                    + e.getKey()
                                    + " not configured (M-16 ARREAR_PAY_CODE)");
                }
                out.add(new Earning(code, e.getValue().setScale(0, RoundingMode.HALF_UP)));
            }
            return out;
        }

        @Override
        public List<Line> deductions(Member member) {
            BigDecimal due = split(member).recovery();
            if (due.signum() == 0) {
                return List.of();
            }
            Integer code = codes.get(RECOVERY);
            if (code == null) {
                throw new NotComputable(
                        "Arrear recovery pay code not configured (M-16 ARREAR_PAY_CODE RECOVERY)");
            }
            NetPayFloors.Floor f =
                    floor.orElseThrow(
                            () -> new NotComputable("Net-pay floor not configured (M-14)"));
            BigDecimal amount =
                    ArrearRules.recoverable(
                                    due.setScale(0, RoundingMode.HALF_UP),
                                    member.gross(),
                                    member.deducted(),
                                    f.amount(member.gross()))
                            .setScale(0, RoundingMode.DOWN);
            return amount.signum() > 0 ? List.of(new Line(code, amount, null, due)) : List.of();
        }

        /** The member's differentials due this month, read once for earnings and deductions. */
        private ArrearRules.Split split(Member member) {
            Object[] cached = last.get();
            if (cached != null
                    && cached[0].equals(member.employeeId())
                    && cached[1].equals(member.month())) {
                return (ArrearRules.Split) cached[2];
            }
            String yymm =
                    String.format(
                            "%04d%02d", member.month().getYear(), member.month().getMonthValue());
            List<ArrearRules.Differential> due =
                    jdbc.query(
                            "SELECT c.legacy_pay_code, d.arrear_amount FROM arrear_detail d JOIN"
                                + " arrear_event e ON e.arrear_event_id = d.arrear_event_id JOIN"
                                + " pay_component c ON c.component_id = d.component_id WHERE"
                                + " d.employee_id = ? AND d.arrear_status = 'APPROVED' AND"
                                + " e.event_status = 'APPROVED' AND e.posted_to_yymm <= ?",
                            (rs, i) ->
                                    new ArrearRules.Differential(rs.getInt(1), rs.getBigDecimal(2)),
                            member.employeeId(),
                            yymm);
            ArrearRules.Split split = ArrearRules.split(due);
            last.set(new Object[] {member.employeeId(), member.month(), split});
            return split;
        }
    }
}
