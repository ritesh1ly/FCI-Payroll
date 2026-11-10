package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.tax.business.DeclarationRules;
import jakarta.persistence.EntityManagerFactory;
import java.time.LocalDate;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

/**
 * I/O profile of the Module 08 lists (owner 2026-10-25: profile every module; SRS-NF-3, SRS-NF-6):
 * the SQL statements a page costs do not grow with the page size, so a list never issues one query
 * per row (N+1). Hibernate statistics count the statements of one request; the demo seed holds more
 * than 100 rows of the year before for the I-07 and I-18 registers (V039_9_1).
 */
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class TaxQueryCountIT extends ApiTestSupport {

    private static final String PREVIOUS =
            DeclarationRules.finYearOf(LocalDate.now(TaxYearFixtures.INDIA).minusYears(1));

    @Autowired private EntityManagerFactory emf;

    private long statementsOf(Session s, String path) throws Exception {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        MvcResult r = mvc.perform(userGet(s, path)).andReturn();
        assertThat(r.getResponse().getStatus()).as(path).isEqualTo(200);
        return stats.getPrepareStatementCount();
    }

    private void assertFlat(Session s, String path) throws Exception {
        long small = statementsOf(s, path + "&page=0&size=20");
        long large = statementsOf(s, path + "&page=0&size=100");
        // One statement per related table at most, whatever the page size: 100 rows may cost a
        // few statements more than 20 (an IN list split in batches), never one per row.
        assertThat(large)
                .as(path + ": statements at 100 rows vs 20 (" + small + ")")
                .isLessThanOrEqualTo(small + 3);
        assertThat(large).as(path).isLessThan(40);
    }

    @Test
    void SRS_NF_3_theRegistersCostTheSameStatementsAtTwentyAndAHundredRows() throws Exception {
        Session maker = login("tax.maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        assertFlat(maker, "/v1/tax/declarations?finYear=" + PREVIOUS);
        assertFlat(maker, "/v1/tax/savings?finYear=" + PREVIOUS);
        assertFlat(maker, "/v1/tax/form16-certificates?finYear=" + PREVIOUS);
        assertFlat(maker, "/v1/tax/returns?finYear=" + PREVIOUS);
        Session admin = login("su.hq@fci-demo.test", "RULE_ADMINISTRATOR", "HQ001");
        assertFlat(admin, "/v1/tax/sections?x=1");
        assertFlat(admin, "/v1/tax/pt-states?x=1");
    }

    @Test
    void SRS_NF_6_aMembersYearAndTheCatalogueAreReadInABoundedNumberOfStatements()
            throws Exception {
        Session maker = login("tax.maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        String year = DeclarationRules.finYearOf(LocalDate.now(TaxYearFixtures.INDIA));
        assertThat(statementsOf(maker, "/v1/tax/forecasts/870001?finYear=" + year)).isLessThan(30);
        assertThat(statementsOf(maker, "/v1/tax/savings/members/870001?finYear=" + year))
                .isLessThan(30);
        Session admin = login("su.hq@fci-demo.test", "RULE_ADMINISTRATOR", "HQ001");
        assertThat(statementsOf(admin, "/v1/tax/output-definitions?x=1")).isLessThan(40);
    }
}
