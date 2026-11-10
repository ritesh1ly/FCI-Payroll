package in.gov.fci.payroll.organisation.api;

import in.gov.fci.payroll.organisation.business.model.BankBranch;
import in.gov.fci.payroll.organisation.business.model.BankMaster;
import in.gov.fci.payroll.organisation.business.model.OrgDesignation;
import in.gov.fci.payroll.organisation.business.model.PayScaleBand;
import in.gov.fci.payroll.organisation.persistence.OrgRepositories;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** The small organisation masters other modules display (designations, pay scale bands). */
@Component
public class OrgMasters {

    private final OrgRepositories.Designations designations;
    private final OrgRepositories.Bands bands;
    private final OrgRepositories.Branches branches;
    private final OrgRepositories.Banks banks;

    public OrgMasters(
            OrgRepositories.Designations designations,
            OrgRepositories.Bands bands,
            OrgRepositories.Branches branches,
            OrgRepositories.Banks banks) {
        this.designations = designations;
        this.bands = bands;
        this.branches = branches;
        this.banks = banks;
    }

    /** The bank code of each branch, by branch id (M-01 Bank column). */
    @Transactional(readOnly = true)
    public Map<Long, String> bankCodesByBranch() {
        Map<Long, String> codes =
                banks.findAll().stream()
                        .collect(Collectors.toMap(BankMaster::getId, BankMaster::getCode));
        return branches.findAll().stream()
                .filter(b -> codes.containsKey(b.getBankId()))
                .collect(Collectors.toMap(BankBranch::getId, b -> codes.get(b.getBankId())));
    }

    @Transactional(readOnly = true)
    public Map<Long, OrgDesignation> designationsById() {
        return designations.findAll().stream()
                .collect(Collectors.toMap(OrgDesignation::getId, Function.identity()));
    }

    @Transactional(readOnly = true)
    public Map<Long, PayScaleBand> payBandsById() {
        return bands.findAll().stream()
                .collect(Collectors.toMap(PayScaleBand::getId, Function.identity()));
    }
}
