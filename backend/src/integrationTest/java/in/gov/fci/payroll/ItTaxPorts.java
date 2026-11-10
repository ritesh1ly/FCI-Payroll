package in.gov.fci.payroll;

import in.gov.fci.payroll.tax.business.PostingLedger;
import in.gov.fci.payroll.tax.business.ReturnValidator;
import in.gov.fci.payroll.tax.business.TaxableEarnings;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The Module 08 ports in the integration tests: the payroll engines' earnings, the posting
 * interface and the prescribed validation utility do not exist yet, so a test supplies their
 * answers here. Until a test does, every answer is empty, exactly as the shipped "not configured"
 * implementations (plan 08 D-4), so no other test sees a difference.
 */
@Component
@Primary
@Profile("it")
public class ItTaxPorts implements TaxableEarnings, PostingLedger, ReturnValidator {

    /** employeeId|finYear -> the year the engines would provide. */
    public static final Map<String, Year> YEARS = new ConcurrentHashMap<>();

    /** orgUnitId -> the posted TDS of each month (YYYYMM). */
    public static final Map<Long, Map<String, BigDecimal>> POSTED = new ConcurrentHashMap<>();

    /** CIN|BSR|date of the challans that map to a posted remittance. */
    public static final Set<String> REMITTED = ConcurrentHashMap.newKeySet();

    /** Members whose earnings the engines cannot give now (a failing run, restarted later). */
    public static final Set<Long> FAILING = ConcurrentHashMap.newKeySet();

    /** returnId -> the utility's verdict. */
    public static final Map<Long, Verdict> VERDICTS = new ConcurrentHashMap<>();

    /** A year of twelve equal months, all actual (locked) or all projected. */
    public static Year year(
            String finYear, String monthlyGross, String monthlyTds, boolean actual) {
        return year(finYear, monthlyGross, monthlyTds, actual, 0, 11);
    }

    /** As above, with TDS deducted only in the months {@code from} … {@code to} (0 = April). */
    public static Year year(
            String finYear,
            String monthlyGross,
            String monthlyTds,
            boolean actual,
            int from,
            int to) {
        int start = Integer.parseInt(finYear.substring(0, 4));
        List<Month> months =
                java.util.stream.IntStream.rangeClosed(4, 15)
                        .mapToObj(
                                m ->
                                        new Month(
                                                String.format(
                                                        "%04d%02d",
                                                        m > 12 ? start + 1 : start,
                                                        m > 12 ? m - 12 : m),
                                                actual,
                                                new BigDecimal(monthlyGross),
                                                actual && m - 4 >= from && m - 4 <= to
                                                        ? new BigDecimal(monthlyTds)
                                                        : BigDecimal.ZERO,
                                                BigDecimal.ZERO,
                                                List.of(
                                                        new Line(
                                                                "Basic Pay",
                                                                new BigDecimal(monthlyGross)))))
                        .toList();
        return new Year(months);
    }

    @Override
    public Optional<Year> year(Long employeeId, String finYear) {
        if (FAILING.contains(employeeId)) {
            throw new IllegalStateException("IT: the payroll engines did not answer");
        }
        return Optional.ofNullable(YEARS.get(employeeId + "|" + finYear));
    }

    @Override
    public Optional<BigDecimal> postedTds(Long orgUnitId, Collection<String> months) {
        Map<String, BigDecimal> posted = POSTED.get(orgUnitId);
        if (posted == null) {
            return Optional.empty();
        }
        return Optional.of(
                months.stream()
                        .map(m -> posted.getOrDefault(m, BigDecimal.ZERO))
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    @Override
    public Optional<Boolean> remitted(String cinNumber, String bsrCode, LocalDate challanDate) {
        if (REMITTED.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(REMITTED.contains(cinNumber + "|" + bsrCode + "|" + challanDate));
    }

    @Override
    public Optional<Verdict> validate(Long returnId) {
        return Optional.ofNullable(VERDICTS.get(returnId));
    }
}
