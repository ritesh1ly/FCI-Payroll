package in.gov.fci.payroll.tax.business;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Optional;

/**
 * The posted payroll and the remittances of a paying unit, as Finance posts them (module 23, EBS /
 * APPS; SRS-7-8, SRS-7-19, BR-TAX-011, BR-TAX-012). A port: every statutory certificate and return
 * reconciles to them before release. Until the posting interface exists the shipped implementation
 * answers empty, and nothing is released on assumed figures.
 */
public interface PostingLedger {

    /**
     * The TDS posted for the unit in the months (YYYYMM); empty while postings are not available.
     */
    Optional<BigDecimal> postedTds(Long orgUnitId, Collection<String> months);

    /**
     * Whether the challan maps to a posted remittance (SRS C-11 "Challan Reference must map to a
     * posted remittance"); empty while remittances are not available.
     */
    Optional<Boolean> remitted(String cinNumber, String bsrCode, java.time.LocalDate challanDate);
}
