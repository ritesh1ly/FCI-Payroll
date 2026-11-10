package in.gov.fci.payroll.tax.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * {@code tax_challan}: a TDS challan of a paying unit as imported from the challan file (target DDL
 * v4.0 Section 11; SRS-7-7, SRS-7-21). Unique by CIN, BSR code and date ({@code uk_tax_challan}); a
 * return maps its deductees to it and it must map to a posted remittance. Never changed.
 */
@Entity
@Table(name = "tax_challan")
public class TaxChallan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "challan_id")
    private Long challanId;

    @Column(name = "cin_number", length = 30)
    private String cinNumber;

    @Column(name = "bsr_code", length = 20)
    private String bsrCode;

    @Column(name = "challan_date")
    private LocalDate challanDate;

    @Column(name = "challan_amount", precision = 18, scale = 2)
    private BigDecimal challanAmount;

    @Column(name = "payment_reference", length = 50)
    private String paymentReference;

    @Column(name = "fin_year", nullable = false, length = 9)
    private String finYear;

    @Column(name = "period_code", length = 10)
    private String periodCode;

    @Column(name = "org_unit_id")
    private Long orgUnitId;

    protected TaxChallan() {}

    @SuppressWarnings("java:S107") // the DDL row, one argument per column
    public static TaxChallan imported(
            String cin,
            String bsr,
            LocalDate date,
            BigDecimal amount,
            String reference,
            String finYear,
            String period,
            Long unit) {
        TaxChallan c = new TaxChallan();
        c.cinNumber = cin;
        c.bsrCode = bsr;
        c.challanDate = date;
        c.challanAmount = amount;
        c.paymentReference = reference;
        c.finYear = finYear;
        c.periodCode = period;
        c.orgUnitId = unit;
        return c;
    }

    public Long getChallanId() {
        return challanId;
    }

    public String getCinNumber() {
        return cinNumber;
    }

    public String getBsrCode() {
        return bsrCode;
    }

    public LocalDate getChallanDate() {
        return challanDate;
    }

    public BigDecimal getChallanAmount() {
        return challanAmount;
    }

    public String getPaymentReference() {
        return paymentReference;
    }

    public String getFinYear() {
        return finYear;
    }

    public String getPeriodCode() {
        return periodCode;
    }

    public Long getOrgUnitId() {
        return orgUnitId;
    }
}
