package in.gov.fci.payroll.organisation.persistence;

import in.gov.fci.payroll.organisation.business.model.BankBranch;
import in.gov.fci.payroll.organisation.business.model.BankMaster;
import in.gov.fci.payroll.organisation.business.model.OrgDepartment;
import in.gov.fci.payroll.organisation.business.model.OrgDesignation;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.organisation.business.model.OrgZone;
import in.gov.fci.payroll.organisation.business.model.PayScaleBand;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/** Organisation and reference masters (read-mostly, cached by OrgDirectory). */
public final class OrgRepositories {

    private OrgRepositories() {}

    public interface Zones extends Repository<OrgZone, Long> {
        List<OrgZone> findAllByOrderByCode();

        Optional<OrgZone> findByCode(String code);
    }

    public interface Locations extends Repository<OrgLocation, Long> {
        List<OrgLocation> findAllByOrderByCode();

        Optional<OrgLocation> findByCode(String code);
    }

    public interface Departments extends Repository<OrgDepartment, Long> {
        List<OrgDepartment> findAllByOrderByCode();
    }

    public interface Designations extends Repository<OrgDesignation, Long> {
        List<OrgDesignation> findAll();
    }

    public interface Bands extends Repository<PayScaleBand, Long> {
        List<PayScaleBand> findAll();
    }

    public interface Branches extends Repository<BankBranch, Long> {
        List<BankBranch> findAll();
    }

    public interface Banks extends Repository<BankMaster, Long> {
        List<BankMaster> findAll();
    }
}
