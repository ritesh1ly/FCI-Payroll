package in.gov.fci.payroll.integration.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.integration.api.HrmsApi;
import in.gov.fci.payroll.integration.api.InterfaceSettings;
import in.gov.fci.payroll.integration.api.InterfaceSettings.Settings;
import in.gov.fci.payroll.integration.config.HrmsApiProperties;
import in.gov.fci.payroll.integration.config.HrmsApiProperties.ApiUrl;
import in.gov.fci.payroll.integration.config.HrmsApiProperties.Limit;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * X-03 HRMS API view (SRS-INT-16): a URL setting shows as its path only, never a host or secret.
 */
class HrmsApiCatalogServiceTest {

    @Test
    void SRS_INT_16_aUrlShowsAsItsPathOnly() {
        assertThat(
                        HrmsApiCatalogService.pathOnly(
                                "https://user:secret@hrms.example.invalid:8443/a/b?key=abc#frag"))
                .isEqualTo("/a/b");
        assertThat(HrmsApiCatalogService.pathOnly("http://hrms.example.invalid")).isEqualTo("/");
        assertThat(HrmsApiCatalogService.pathOnly("hrms/request-ota?x=1"))
                .isEqualTo("/hrms/request-ota");
        assertThat(HrmsApiCatalogService.pathOnly("/hrms/request-ota#top"))
                .isEqualTo("/hrms/request-ota");
    }

    @Test
    void SRS_INT_16_nothingConfiguredOrUnreadableShowsNoPath() {
        assertThat(HrmsApiCatalogService.pathOnly(null)).isNull();
        assertThat(HrmsApiCatalogService.pathOnly("  ")).isNull();
        assertThat(HrmsApiCatalogService.pathOnly("https://bad host/x")).isNull();
    }

    @Test
    void SRS_INT_32_theTokenApiIsCountedUnderTheAuthenticationInterface() {
        assertThat(HrmsApiCatalogService.interfaceCodeOf(HrmsApi.TOKEN)).isEqualTo("HRMS_API_AUTH");
        assertThat(HrmsApiCatalogService.interfaceCodeOf(HrmsApi.REQUEST_OTA))
                .isEqualTo("HRMS_EMP_OTA");
        assertThat(HrmsApiCatalogService.interfaceCodeOf(HrmsApi.GET_OTA_STATUS))
                .isEqualTo("HRMS_PAYMENT_STATUS");
    }

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Instant NOW = Instant.now();

    private final AccessContext context = mock(AccessContext.class);
    private final Authorization policy = mock(Authorization.class);
    private final InterfaceSettings settings = mock(InterfaceSettings.class);

    /**
     * Per interface code: last run instant (null for none), its status, the id, signed-off flag.
     */
    private final Map<String, Object[]> registry = new HashMap<>();

    private Long refused = 0L;

    private HrmsApiCatalogService service(HrmsApiProperties properties) {
        IntegrationStore store = mock(IntegrationStore.class);
        JdbcTemplate jdbc =
                mock(
                        JdbcTemplate.class,
                        invocation -> {
                            String method = invocation.getMethod().getName();
                            if (method.equals("queryForObject")) {
                                return refused;
                            }
                            String sql = invocation.getArgument(0);
                            if (sql.contains("FROM interface_registry")) {
                                Object[] r = registry.get((String) invocation.getArgument(1));
                                return r == null
                                        ? List.of()
                                        : List.of(
                                                Map.of(
                                                        "INTERFACE_ID",
                                                        r[2],
                                                        "SPEC_SIGNED_OFF",
                                                        r[3]));
                            }
                            if (sql.contains("SUM(")) {
                                return List.of(Map.of("R", 5L, "A", 3, "J", 2L));
                            }
                            Long id = invocation.getArgument(1);
                            Object[] last =
                                    registry.values().stream()
                                            .filter(r -> r[2].equals(id))
                                            .findFirst()
                                            .orElseThrow();
                            return last[0] == null
                                    ? List.of()
                                    : List.of(
                                            Map.of(
                                                    "STARTED_AT",
                                                    Timestamp.from((Instant) last[0]),
                                                    "RUN_STATUS",
                                                    last[1]));
                        });
        when(store.jdbc()).thenReturn(jdbc);
        when(settings.inForce(eq("HRMS_EMP_OTA")))
                .thenReturn(
                        Optional.of(
                                new Settings(
                                        "HRMS_EMP_OTA",
                                        3,
                                        10,
                                        "FIXED",
                                        20,
                                        false,
                                        null,
                                        null,
                                        null,
                                        LocalDate.now(IST),
                                        true)));
        when(settings.inForce(eq("HRMS_PAYMENT_STATUS"))).thenReturn(Optional.empty());
        return new HrmsApiCatalogService(
                store, properties, settings, policy, Clock.fixed(NOW, IST));
    }

    private static HrmsApiProperties properties() {
        Map<String, ApiUrl> urls = new HashMap<>();
        urls.put(HrmsApi.REQUEST_OTA.code(), new ApiUrl("hrms/request-ota?k=1"));
        urls.put(HrmsApi.TOKEN.code(), new ApiUrl("https://payroll.example.invalid/hrms/token"));
        urls.put(HrmsApi.GET_OTA_STATUS.code(), new ApiUrl("get-ota-status", "OUTBOUND"));
        urls.put(HrmsApi.CANCEL_OTA.code(), new ApiUrl("cancel-ota", "OUTBOUND"));
        urls.put(HrmsApi.REQUEST_LOAN_RESCHEDULE.code(), new ApiUrl(null));
        Map<String, Limit> limits = new HashMap<>();
        limits.put(HrmsApi.GET_OTA_STATUS.code(), new Limit(2, 100));
        return new HrmsApiProperties("https://hrms.example.invalid/api", urls, limits, null);
    }

    private static HrmsApiCatalogService.Row row(
            Paged<HrmsApiCatalogService.Row> page, HrmsApi api) {
        return page.items().stream()
                .filter(r -> r.code().equals(api.code()))
                .findFirst()
                .orElseThrow();
    }

    private static final PageSpec ALL = new PageSpec(0, 500);

    @Test
    void SRS_INT_17_theViewNeedsTheX03ViewGateAndListsEveryApiWithItsActivity() {
        registry.put("HRMS_EMP_OTA", new Object[] {NOW.minusSeconds(60), "PARTIAL", 11L, "Y"});
        registry.put("HRMS_PAYMENT_STATUS", new Object[] {null, null, 12L, "N"});
        registry.put("HRMS_API_AUTH", new Object[] {NOW.minusSeconds(5), "OK", 13L, "N"});
        refused = 4L;
        var page = service(properties()).list(context, null, null, null, null, null, ALL);
        verify(policy).require(context, "X-03", "VIEW");
        assertThat(page.total()).isEqualTo(HrmsApi.values().length);
        var ota = row(page, HrmsApi.REQUEST_OTA);
        assertThat(ota.configured()).isTrue();
        assertThat(ota.path()).isEqualTo("/hrms/request-ota");
        assertThat(ota.direction()).isEqualTo("INBOUND");
        assertThat(ota.retryCount()).isEqualTo(3);
        assertThat(ota.lastStatus()).isEqualTo("PARTIAL");
        assertThat(ota.received()).isEqualTo(5L);
        assertThat(ota.accepted()).isEqualTo(3L);
        assertThat(ota.rejected()).isEqualTo(2L);
        assertThat(ota.refused()).isEqualTo(4L);
        assertThat(ota.specSignedOff()).isTrue();
        assertThat(ota.days()).isEqualTo(HrmsApiCatalogService.DEFAULT_DAYS);
        var token = row(page, HrmsApi.TOKEN);
        assertThat(token.path()).isEqualTo("/hrms/token");
        assertThat(token.received()).isEqualTo(5L);
        var status = row(page, HrmsApi.GET_OTA_STATUS);
        assertThat(status.direction()).isEqualTo("OUTBOUND");
        assertThat(status.configured()).isTrue();
        assertThat(status.lastStatus()).isEqualTo("NONE");
        assertThat(status.lastCallAt()).isNull();
        assertThat(status.received()).isNull();
        assertThat(status.refused()).isNull();
        assertThat(status.bulkheadMaxConcurrentCalls()).isEqualTo(2);
        assertThat(status.bulkheadMaxWaitMillis()).isEqualTo(100);
        assertThat(status.retryCount()).isNull();
        var unset = row(page, HrmsApi.REQUEST_LEAVE_ENCASHMENT);
        assertThat(unset.configured()).isFalse();
        assertThat(unset.path()).isNull();
        assertThat(row(page, HrmsApi.REQUEST_LOAN_RESCHEDULE).configured()).isFalse();
    }

    @Test
    void SRS_INT_17_anInterfaceNotInTheRegistryShowsNoActivityAndANullRefusalCountIsZero() {
        refused = null;
        registry.put("HRMS_EMP_OTA", new Object[] {NOW, "OK", 11L, "N"});
        var page = service(properties()).list(context, null, null, null, null, null, ALL);
        var loan = row(page, HrmsApi.REQUEST_LOAN_RESCHEDULE);
        assertThat(loan.lastStatus()).isEqualTo("NONE");
        assertThat(loan.received()).isZero();
        assertThat(loan.specSignedOff()).isFalse();
        assertThat(row(page, HrmsApi.REQUEST_OTA).refused()).isZero();
    }

    @Test
    void SRS_INT_17_anOutboundApiWithNoBaseAddressIsNotConfiguredAndHasNoPath() {
        var noBase =
                new HrmsApiProperties(
                        null,
                        Map.of(
                                HrmsApi.GET_OTA_STATUS.code(),
                                new ApiUrl("get-ota-status", "OUTBOUND")),
                        null,
                        null);
        var page = service(noBase).list(context, null, null, null, null, null, ALL);
        var status = row(page, HrmsApi.GET_OTA_STATUS);
        assertThat(status.configured()).isFalse();
        assertThat(status.path()).isNull();
        assertThat(status.bulkheadMaxConcurrentCalls()).isNull();
        var none =
                service(new HrmsApiProperties(null, null))
                        .list(context, null, null, null, null, null, ALL);
        assertThat(none.items()).allMatch(r -> !r.configured());
    }

    @Test
    void SRS_INT_17_theFiltersNarrowTheListAndEachOneCanMatchNothing() {
        registry.put("HRMS_EMP_OTA", new Object[] {NOW, "FAILED", 11L, "N"});
        var svc = service(properties());
        assertThat(svc.list(context, "REQUIRED", null, null, null, null, ALL).items())
                .allMatch(r -> r.selectionClass().equals("REQUIRED"))
                .isNotEmpty();
        assertThat(svc.list(context, "RULE_PENDING", null, null, null, null, ALL).items())
                .allMatch(r -> r.selectionClass().equals("RULE_PENDING"));
        assertThat(svc.list(context, "NOPE", null, null, null, null, ALL).items()).isEmpty();
        assertThat(svc.list(context, null, "OUTBOUND", null, null, null, ALL).items())
                .extracting(HrmsApiCatalogService.Row::code)
                .containsExactlyInAnyOrder("get_ota_status", "cancel_ota");
        assertThat(svc.list(context, null, "INBOUND", null, null, null, ALL).total())
                .isEqualTo(HrmsApi.values().length - 2);
        assertThat(svc.list(context, null, null, true, null, null, ALL).items())
                .allMatch(HrmsApiCatalogService.Row::configured);
        assertThat(svc.list(context, null, null, false, null, null, ALL).items())
                .noneMatch(HrmsApiCatalogService.Row::configured);
        assertThat(svc.list(context, null, null, null, "FAILED", null, ALL).items())
                .extracting(HrmsApiCatalogService.Row::code)
                .contains("request_ota");
        assertThat(svc.list(context, null, null, null, "OK", null, ALL).items()).isEmpty();
        assertThat(svc.list(context, "REQUIRED", "INBOUND", true, "FAILED", 30, ALL).items())
                .extracting(HrmsApiCatalogService.Row::code)
                .containsExactly("request_ota");
    }

    @Test
    void SRS_INT_17_theWindowIsOneToNinetyDaysAndThePageStopsAtTheEnd() {
        var svc = service(properties());
        assertThat(svc.list(context, null, null, null, null, 0, ALL).items().getFirst().days())
                .isEqualTo(1);
        assertThat(svc.list(context, null, null, null, null, -9, ALL).items().getFirst().days())
                .isEqualTo(1);
        assertThat(svc.list(context, null, null, null, null, 365, ALL).items().getFirst().days())
                .isEqualTo(90);
        assertThat(svc.list(context, null, null, null, null, 90, ALL).items().getFirst().days())
                .isEqualTo(90);
        int total = HrmsApi.values().length;
        var first = svc.list(context, null, null, null, null, null, new PageSpec(0, 10));
        assertThat(first.items()).hasSize(10);
        assertThat(first.total()).isEqualTo(total);
        var last = svc.list(context, null, null, null, null, null, new PageSpec(total / 10, 10));
        assertThat(last.items()).hasSize(total % 10);
        var past = svc.list(context, null, null, null, null, null, new PageSpec(1000, 100));
        assertThat(past.items()).isEmpty();
        assertThat(past.total()).isEqualTo(total);
    }

    @Test
    void SRS_INT_17_aCallerWithoutTheViewGateGetsNothing() {
        doThrow(new FciException(CommonErrorCode.NOT_FOUND))
                .when(policy)
                .require(any(), eq("X-03"), eq("VIEW"));
        var svc = service(properties());
        assertThatThrownBy(
                        () -> svc.list(context, null, null, null, null, null, new PageSpec(0, 20)))
                .isInstanceOf(FciException.class);
    }

    @Test
    void SRS_INT_16_aFullUrlWithNoPathPartShowsTheRootAndAQueryOnlySettingShowsNoQuery() {
        assertThat(HrmsApiCatalogService.pathOnly("https://hrms.example.invalid?x=1"))
                .isEqualTo("/");
        assertThat(HrmsApiCatalogService.pathOnly("?only=query")).isEqualTo("/");
        assertThat(HrmsApiCatalogService.pathOnly("//double//slash")).isEqualTo("/double//slash");
    }
}
