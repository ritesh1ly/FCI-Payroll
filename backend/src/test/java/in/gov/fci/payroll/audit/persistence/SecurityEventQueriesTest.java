package in.gov.fci.payroll.audit.persistence;

import static org.assertj.core.api.Assertions.assertThatCode;

import in.gov.fci.payroll.audit.business.model.SecurityEventRecord;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.query.parser.PartTree;

/** OD-130: the derived queries name real fields (a wrong one stops every context at start-up). */
class SecurityEventQueriesTest {

    @Test
    void OD_130_theDerivedQueriesParse() {
        for (String query :
                List.of(
                        "countByActingUserAndEventTypeAndOccurredAtGreaterThan",
                        "existsByActingUserAndEventTypeAndTargetEntity",
                        "findFirstByEventTypeAndTargetEntityAndTargetKeyOrderByOccurredAtDesc",
                        "findByActingUserAndEventTypeAndOccurredAtGreaterThanOrderByOccurredAtDesc",
                        "findByActingUserAndEventTypeInOrderByOccurredAtDesc")) {
            assertThatCode(() -> new PartTree(query, SecurityEventRecord.class))
                    .doesNotThrowAnyException();
        }
    }
}
