package in.gov.fci.payroll.configuration.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.business.model.LabelMaster;
import in.gov.fci.payroll.configuration.persistence.ConfigurationRepositories;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.LocationType;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * NEW-G1-05, NEW-G1-06: SRS-G-39 master-value names - the label a user sees (language and office,
 * region, Global; then English Global) and a search by any Active label.
 */
class MasterLabelsTest {

    private static final Instant NOW = Instant.parse("2026-11-02T06:00:00Z");
    private static final LocalDate FROM = LocalDate.of(2026, 4, 1);
    private static final long OFFICE = 4L;
    private static final long REGION = 3L;

    private final ConfigurationRepositories.Labels labels =
            mock(ConfigurationRepositories.Labels.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final MasterLabels names =
            new MasterLabels(labels, org, Clock.fixed(NOW, ZoneOffset.UTC));

    private static LabelMaster label(
            String code, String language, LabelMaster.Scope scope, Long location, String text) {
        LabelMaster l =
                LabelMaster.propose(
                        new LabelMaster.Particulars(
                                "OPR." + code + "." + text,
                                LabelMaster.Type.MASTER_VALUE,
                                MasterLabels.OPERATION,
                                code,
                                language,
                                scope,
                                location,
                                text,
                                true,
                                FROM),
                        1,
                        "it",
                        "test",
                        NOW);
        l.decided(true, "checker", NOW);
        return l;
    }

    @BeforeEach
    void wire() {
        OrgLocation region = mock(OrgLocation.class);
        when(region.getId()).thenReturn(REGION);
        when(org.ancestorOrSelf(OFFICE, LocationType.RO)).thenReturn(Optional.of(region));
        LabelMaster pending =
                LabelMaster.propose(
                        new LabelMaster.Particulars(
                                "OPR.W2P.P",
                                LabelMaster.Type.MASTER_VALUE,
                                MasterLabels.OPERATION,
                                "W2P",
                                "EN",
                                LabelMaster.Scope.GLOBAL,
                                null,
                                "Pending Wagon",
                                true,
                                FROM),
                        1,
                        "it",
                        "test",
                        NOW);
        when(labels.findByMasterEntity(MasterLabels.OPERATION))
                .thenReturn(
                        List.of(
                                label("STACK", "EN", LabelMaster.Scope.GLOBAL, null, "Stacking"),
                                label("STACK", "HI", LabelMaster.Scope.GLOBAL, null, "Thappi"),
                                label("STACK", "EN", LabelMaster.Scope.REGION, REGION, "Chatta"),
                                label("T2P", "EN", LabelMaster.Scope.OFFICE, OFFICE, "Utarai"),
                                pending));
    }

    @Test
    void SRS_G_39_SRS_DM_15_theLabelShownFollowsTheOrderOfficeRegionGlobalThenEnglish() {
        assertThat(names.shown(MasterLabels.OPERATION, "en", OFFICE))
                .as("office user in English: the region name of STACK, the office name of T2P")
                .containsEntry("STACK", "Chatta")
                .containsEntry("T2P", "Utarai")
                .doesNotContainKey("W2P");
        assertThat(names.shown(MasterLabels.OPERATION, "HI", null))
                .as(
                        "Hindi, no office: the Hindi Global name; T2P has none in Hindi or English"
                                + " Global")
                .containsEntry("STACK", "Thappi")
                .doesNotContainKey("T2P");
        assertThat(names.shown(MasterLabels.OPERATION, null, null))
                .as("no language: English Global")
                .containsEntry("STACK", "Stacking");
        when(org.ancestorOrSelf(9L, LocationType.RO)).thenReturn(Optional.empty());
        assertThat(names.shown(MasterLabels.OPERATION, "EN", 9L))
                .as("an office outside any region")
                .containsEntry("STACK", "Stacking");
    }

    @Test
    void SRS_G_39_searchFindsACodeByAnyActiveLabelIgnoringCase() {
        assertThat(names.matching(MasterLabels.OPERATION, " thap ")).containsExactly("STACK");
        assertThat(names.matching(MasterLabels.OPERATION, "UTARAI")).containsExactly("T2P");
        assertThat(names.matching(MasterLabels.OPERATION, "Pending"))
                .as("a pending label is not searched")
                .isEmpty();
        assertThat(names.matching(MasterLabels.OPERATION, " ")).isEmpty();
        assertThat(names.matching(MasterLabels.OPERATION, null)).isEmpty();
    }
}
