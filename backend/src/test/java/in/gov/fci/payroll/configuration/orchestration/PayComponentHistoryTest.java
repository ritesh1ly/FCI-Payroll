package in.gov.fci.payroll.configuration.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.RecordHistorySource;
import in.gov.fci.payroll.audit.api.RecordHistorySource.HistoryRecord;
import in.gov.fci.payroll.configuration.business.ComponentRules;
import in.gov.fci.payroll.configuration.business.model.PayComponent;
import in.gov.fci.payroll.configuration.persistence.ConfigurationRepositories;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * M-07 pay components on X-04 and the M-07 History button (SRS-20-29, SRS-20-3; SRS-19-1): a
 * component is one row revised in place, its current value the image its approved changes are
 * audited with, not in force before its effective-from; a key that is no pay code, or a code not in
 * the catalogue, is no record.
 */
class PayComponentHistoryTest {

    private final ConfigurationRepositories.PayComponents components =
            mock(ConfigurationRepositories.PayComponents.class);
    private final RecordHistorySource source =
            new ConfigurationHistory().payComponentHistory(components);

    @Test
    void SRS_20_29_aComponentIsOneRowWithItsAuditedImage() {
        PayComponent basic =
                PayComponent.defined(
                        new ComponentRules.Definition(
                                101,
                                "Basic Pay",
                                "EARNING",
                                "PAY",
                                null,
                                "CALC",
                                "510101",
                                "Y",
                                true,
                                Set.of("STAFF"),
                                LocalDate.of(2016, 1, 1)));
        when(components.findByLegacyPayCode(101)).thenReturn(Optional.of(basic));
        assertThat(source.entity()).isEqualTo("pay_component");
        HistoryRecord record = source.find("101").orElseThrow();
        assertThat(record.isVersioned()).isFalse();
        assertThat(record.screen()).isEqualTo("M-07");
        assertThat(record.locationId()).isNull();
        assertThat(record.auditKeys()).containsExactly("101");
        assertThat(record.existsFrom()).isEqualTo(LocalDate.of(2016, 1, 1));
        assertThat(record.current())
                .containsOnlyKeys("component")
                .containsEntry("component", ComponentCatalogueEffect.image(basic));
    }

    /**
     * US-GAP-298 (SRS-LV-15, BR-LEAVE-007): "Subject to LWP" is part of the audited image, so a
     * change of it alone shows on X-04; a definition that does not state it (an item submitted
     * before V077_1) is not subject to LWP.
     */
    @Test
    void SRS_LV_15_theImageCarriesSubjectToLwp() {
        PayComponent before =
                PayComponent.defined(
                        new ComponentRules.Definition(
                                111,
                                "DA",
                                "EARNING",
                                "PAY",
                                null,
                                "CALC",
                                "510102",
                                "Y",
                                true,
                                Set.of("STAFF"),
                                LocalDate.of(2016, 1, 1)));
        assertThat(before.isLwpSubjectFlag()).isFalse();
        assertThat(ComponentCatalogueEffect.image(before).split("\\|")).contains("NOLWP");
        PayComponent subject =
                PayComponent.defined(
                        new ComponentRules.Definition(
                                111,
                                "DA",
                                "EARNING",
                                "PAY",
                                null,
                                "CALC",
                                "510102",
                                "Y",
                                true,
                                Set.of("STAFF"),
                                LocalDate.of(2016, 1, 1),
                                true));
        assertThat(ComponentCatalogueEffect.image(subject).split("\\|"))
                .contains("LWP")
                .doesNotContain("NOLWP");
        assertThat(ComponentCatalogueEffect.image(subject))
                .isNotEqualTo(ComponentCatalogueEffect.image(before));
    }

    @Test
    void SRS_20_29_aKeyThatIsNoPayCodeOrNotInTheCatalogueIsNoRecord() {
        when(components.findByLegacyPayCode(999)).thenReturn(Optional.empty());
        assertThat(source.find("BASIC")).isEmpty();
        assertThat(source.find("999")).isEmpty();
    }
}
