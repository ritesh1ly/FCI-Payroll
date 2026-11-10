package in.gov.fci.payroll.configuration.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.configuration.business.model.PayComponent;
import in.gov.fci.payroll.configuration.orchestration.ComponentCatalogueService.ComponentRow;
import in.gov.fci.payroll.configuration.orchestration.ComponentCatalogueService.Filter;
import in.gov.fci.payroll.configuration.persistence.ConfigurationRepositories;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** M-07 Pay Component Catalogue (SRS-19-1, plan 06 D-9): its filters and refusals. */
class ComponentCatalogueServiceTest {

    private final ConfigurationRepositories.PayComponents components =
            mock(ConfigurationRepositories.PayComponents.class);
    private final SystemParameters settings = mock(SystemParameters.class);
    private final ComponentCatalogueService service =
            new ComponentCatalogueService(
                    components,
                    mock(MakerChecker.class),
                    mock(Authorization.class),
                    mock(JdbcTemplate.class),
                    mock(ObjectMapper.class),
                    settings);
    private final AccessContext context = mock(AccessContext.class);

    private static ComponentRow row(
            String type, String source, String taxable, boolean cpf, boolean labour) {
        return new ComponentRow(
                101,
                "Basic",
                type,
                "EARN",
                null,
                source,
                "4100",
                taxable,
                cpf,
                List.of("STAFF"),
                labour,
                LocalDate.of(2020, 4, 1),
                null,
                false,
                1,
                false);
    }

    private static void refused(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex ->
                                assertThat(ex.getErrorCode())
                                        .isEqualTo(ApiErrorCode.COMPONENT_DEFINITION));
    }

    @Test
    void SRS_19_1_theCatalogueFiltersByClassTypeSourceTaxAndCpfBase() {
        ComponentRow staff = row("EARNING", "CALC", "Y", true, false);
        ComponentRow labour = row("DEDUCTION", "ENT", "N", false, true);
        assertThat(ComponentCatalogueService.matches(staff, null)).isTrue();
        assertThat(
                        ComponentCatalogueService.matches(
                                labour, new Filter("STAFF", null, null, null, null)))
                .isFalse();
        assertThat(
                        ComponentCatalogueService.matches(
                                staff, new Filter("STAFF", null, null, null, null)))
                .isTrue();
        assertThat(
                        ComponentCatalogueService.matches(
                                staff, new Filter("LABOUR", null, null, null, null)))
                .isFalse();
        assertThat(
                        ComponentCatalogueService.matches(
                                labour, new Filter("LABOUR", null, null, null, null)))
                .isTrue();
        assertThat(
                        ComponentCatalogueService.matches(
                                staff, new Filter(null, "EARNING", "CALC", "Y", "Y")))
                .isTrue();
        assertThat(
                        ComponentCatalogueService.matches(
                                labour, new Filter(null, null, null, null, "N")))
                .isTrue();
        assertThat(
                        ComponentCatalogueService.matches(
                                staff, new Filter(null, "DEDUCTION", null, null, null)))
                .isFalse();
        assertThat(
                        ComponentCatalogueService.matches(
                                staff, new Filter(null, null, "ENT", null, null)))
                .isFalse();
        assertThat(
                        ComponentCatalogueService.matches(
                                staff, new Filter(null, null, null, "N", null)))
                .isFalse();
        assertThat(
                        ComponentCatalogueService.matches(
                                staff, new Filter(null, null, null, null, "N")))
                .isFalse();
    }

    @Test
    void SRS_19_1_theReasonIsRequiredPrintableAndShort() {
        assertThat(ComponentCatalogueService.reason("  new code  ")).isEqualTo("new code");
        refused(() -> ComponentCatalogueService.reason(null));
        refused(() -> ComponentCatalogueService.reason("   "));
        refused(() -> ComponentCatalogueService.reason("x".repeat(501)));
        refused(() -> ComponentCatalogueService.reason("café"));
    }

    @Test
    void SRS_19_1_aCodeIsRetiredOnceFromAPastDayOnAndNeverTwice() {
        when(components.findByLegacyPayCode(7)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.retire(context, 7, LocalDate.now(), "r"))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND));
        PayComponent retired = mock(PayComponent.class);
        when(retired.isIsRetired()).thenReturn(true);
        when(components.findByLegacyPayCode(8)).thenReturn(Optional.of(retired));
        refused(() -> service.retire(context, 8, LocalDate.now(), "r"));
        PayComponent live = mock(PayComponent.class);
        when(live.getEffectiveFrom()).thenReturn(LocalDate.of(2020, 4, 1));
        when(components.findByLegacyPayCode(9)).thenReturn(Optional.of(live));
        refused(() -> service.retire(context, 9, null, "r"));
        refused(() -> service.retire(context, 9, LocalDate.of(2020, 3, 31), "r"));
        when(settings.today()).thenReturn(LocalDate.of(2026, 1, 1));
        refused(() -> service.retire(context, 9, LocalDate.of(2026, 1, 2), "r"));
        refused(() -> service.define(context, null, "r"));
    }

    /**
     * R-ASCII: the M-07 reason is a text area: its line breaks are kept, other controls refused.
     */
    @Test
    void R_ASCII_theReasonOfATextAreaKeepsItsLineBreaks() {
        assertThat(ComponentCatalogueService.reason(" new code\r\nper order\t12 "))
                .isEqualTo("new code\r\nper order\t12");
        assertThat(ComponentCatalogueService.reason("x".repeat(500))).hasSize(500);
        refused(() -> ComponentCatalogueService.reason("bell\u0007"));
    }
}
