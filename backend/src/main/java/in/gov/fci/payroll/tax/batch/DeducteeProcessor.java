package in.gov.fci.payroll.tax.batch;

import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.tax.business.model.ReturnDeductee;
import in.gov.fci.payroll.tax.orchestration.ReturnService;
import in.gov.fci.payroll.tax.orchestration.TaxComputationService;
import org.springframework.batch.infrastructure.item.ItemProcessor;

/**
 * The deductee row of one member in the TAX_24Q_RETURN job (C-11; SRS-7-7, SRS-7-13), from {@link
 * ReturnService#deducteeOf} as on the screen. A member without TDS in the quarter is filtered. On a
 * restart the member's row already written is refigured, never added twice (DDL
 * uk_return_deductee).
 */
public class DeducteeProcessor implements ItemProcessor<TaxMemberReader.Row, ReturnDeductee> {

    private final EmployeeDirectory employees;
    private final ReturnService returns;
    private final ReturnService.Opened opened;
    private final TaxComputationService.Run rates;

    DeducteeProcessor(
            EmployeeDirectory employees,
            ReturnService returns,
            ReturnService.Opened opened,
            TaxComputationService.Run rates) {
        this.employees = employees;
        this.returns = returns;
        this.opened = opened;
        this.rates = rates;
    }

    @Override
    public ReturnDeductee process(TaxMemberReader.Row row) {
        return employees
                .member(row.employeeId())
                .flatMap(
                        m ->
                                returns.deducteeOf(
                                        m,
                                        opened,
                                        rates,
                                        returns.existingRow(opened.returnId(), m.id())))
                .orElse(null);
    }
}
