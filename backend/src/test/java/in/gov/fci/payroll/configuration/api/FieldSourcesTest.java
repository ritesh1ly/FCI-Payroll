package in.gov.fci.payroll.configuration.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * BR-TAX-013: a binding resolves only to a registered field; a binding that names no entity, no
 * field or no id resolves to none without a lookup.
 */
class FieldSourcesTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final FieldSources sources = new FieldSources(jdbc);

    @Test
    void BR_TAX_013_aBindingWithoutItsEntityFieldOrIdResolvesToNone() {
        assertThat(sources.field(null, "pan_number")).isEmpty();
        assertThat(sources.field("employee", null)).isEmpty();
        assertThat(sources.byId(null)).isEmpty();
        verifyNoInteractions(jdbc);
    }
}
