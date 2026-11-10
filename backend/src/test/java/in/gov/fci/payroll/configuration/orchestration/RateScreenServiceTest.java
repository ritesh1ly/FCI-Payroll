package in.gov.fci.payroll.configuration.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.configuration.business.model.RateSet;
import in.gov.fci.payroll.configuration.business.model.RateSetLine;
import in.gov.fci.payroll.configuration.orchestration.RateMasterService.RateMasterView;
import in.gov.fci.payroll.configuration.orchestration.RateMasterService.Screen;
import in.gov.fci.payroll.configuration.orchestration.RateScreenService.VersionStatus;
import in.gov.fci.payroll.configuration.persistence.ConfigurationRepositories;
import in.gov.fci.payroll.configuration.persistence.RateVersionFacts;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/**
 * The M-04 / M-22 reads as Wireframes v6.1 draws them (OD-121; SRS-1-62, SRS-11-15, SRS-G-13): the
 * state of a version, the DA register with its stated base and arrear, the city classes, the M-22
 * filters and export, and the order copy of a version kept to the M-04 catalogue and its gates.
 */
class RateScreenServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 7);

    private final ConfigurationRepositories.RateSets sets =
            mock(ConfigurationRepositories.RateSets.class);
    private final ConfigurationRepositories.RateSetLines lines =
            mock(ConfigurationRepositories.RateSetLines.class);
    private final RateVersionFacts facts = mock(RateVersionFacts.class);
    private final RateMasterService rates = mock(RateMasterService.class);
    private final Authorization policy = mock(Authorization.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private final DocumentStore documents = mock(DocumentStore.class);
    private final AccessContext context = mock(AccessContext.class);
    private final RateScreenService service =
            new RateScreenService(sets, lines, facts, rates, policy, parameters, documents);

    RateScreenServiceTest() {
        when(parameters.today()).thenReturn(TODAY);
    }

    private static RateSet version(
            long id, String code, int no, LocalDate from, LocalDate to, boolean approved) {
        RateSet set = mock(RateSet.class);
        when(set.getRateSetId()).thenReturn(id);
        when(set.getRateSetCode()).thenReturn(code);
        when(set.getVersionNo()).thenReturn(no);
        when(set.getEffectiveFrom()).thenReturn(from);
        when(set.getEffectiveTo()).thenReturn(to);
        when(set.getApprovalId()).thenReturn(id + 100);
        when(set.isApproved()).thenReturn(approved);
        when(set.isPending()).thenReturn(!approved);
        when(set.isInForceOn(TODAY))
                .thenReturn(
                        approved && !from.isAfter(TODAY) && (to == null || !to.isBefore(TODAY)));
        return set;
    }

    private static RateSetLine line(long set, String cityClass, String value) {
        RateSetLine l = mock(RateSetLine.class);
        when(l.getRateSetId()).thenReturn(set);
        when(l.getCityClass()).thenReturn(cityClass);
        when(l.getRateValue()).thenReturn(new BigDecimal(value));
        return l;
    }

    @Test
    void SRS_11_15_aVersionReadsActiveScheduledSupersededAwaitingOrRejected() {
        LocalDate past = TODAY.minusDays(30);
        assertThat(RateScreenService.statusOf(version(1, "DA_IDA", 1, past, null, true), TODAY))
                .isEqualTo(VersionStatus.ACTIVE);
        assertThat(
                        RateScreenService.statusOf(
                                version(2, "DA_IDA", 2, TODAY.plusDays(1), null, true), TODAY))
                .isEqualTo(VersionStatus.SCHEDULED);
        assertThat(
                        RateScreenService.statusOf(
                                version(3, "DA_IDA", 3, past, TODAY.minusDays(1), true), TODAY))
                .isEqualTo(VersionStatus.SUPERSEDED);
        assertThat(RateScreenService.statusOf(version(4, "DA_IDA", 4, past, null, false), TODAY))
                .isEqualTo(VersionStatus.AWAITING_APPROVAL);
        RateSet rejected = version(5, "DA_IDA", 5, past, null, false);
        when(rejected.isPending()).thenReturn(false);
        assertThat(RateScreenService.statusOf(rejected, TODAY)).isEqualTo(VersionStatus.REJECTED);
    }

    @Test
    void SRS_1_62_theDearnessRegisterCarriesTheStatedBaseAndTheArrearOfItsDate() {
        LocalDate from = LocalDate.of(2026, 4, 1);
        RateSet stated = version(1, "DA_IDA", 2, from, null, true);
        RateSet plain = version(2, "DA_CDA", 1, from.minusMonths(3), null, true);
        when(sets.findByRateSetCodeInOrderByRateSetCodeAscVersionNoDesc(
                        List.of("DA_IDA", "DA_CDA")))
                .thenReturn(List.of(stated, plain));
        List<RateSetLine> saved =
                List.of(line(1, null, "53"), line(1, null, "99"), line(2, null, "45"));
        when(lines.findByRateSetIdIn(anyList())).thenReturn(saved);
        when(facts.computedOn(anyList())).thenReturn(Map.of(101L, "Basic + NPA"));
        when(facts.dearnessArrears(anyList()))
                .thenReturn(
                        Map.of(
                                from,
                                new RateVersionFacts.ArrearImpact(
                                        "AE-131", new BigDecimal("500"))));

        List<RateScreenService.DearnessRow> rows = service.dearness(context);

        assertThat(rows).hasSize(2);
        RateScreenService.DearnessRow first = rows.get(0);
        assertThat(first.pattern()).isEqualTo("IDA");
        assertThat(first.rate()).isEqualByComparingTo("53");
        assertThat(first.computedOn()).isEqualTo("Basic + NPA");
        assertThat(first.arrearReference()).isEqualTo("AE-131");
        assertThat(first.arrearAmount()).isEqualByComparingTo("500");
        assertThat(rows.get(1).computedOn()).isNull();
        assertThat(rows.get(1).arrearReference()).isNull();
        assertThat(rows.get(1).arrearAmount()).isNull();
    }

    @Test
    void SRS_11_15_noDearnessVersionIsAnEmptyList() {
        assertThat(service.dearness(context)).isEmpty();
        verify(policy).require(context, "M-04", "VIEW");
        verify(facts, never()).computedOn(anyList());
    }

    @Test
    void SRS_1_3_theCityClassesReadHraOfBothPatternsAndCcaWithTheDoubleRow() {
        RateSet hra = version(1, "HRA", 1, TODAY.minusDays(9), null, true);
        RateSet cda = version(2, "HRA_CDA", 1, TODAY.minusDays(9), null, true);
        RateSet cca = version(3, "CCA", 1, TODAY.minusDays(9), null, true);
        RateSet waiting = version(4, "CCA", 2, TODAY.plusDays(9), null, false);
        when(sets.findByRateSetCodeInOrderByRateSetCodeAscVersionNoDesc(
                        List.of("HRA", "HRA_CDA", "CCA")))
                .thenReturn(List.of(hra, cda, cca, waiting));
        List<RateSetLine> saved =
                List.of(
                        line(1, "X", "30"),
                        line(2, "X", "28"),
                        line(3, "X", "1800"),
                        line(1, null, "1"));
        when(lines.findByRateSetIdIn(anyList())).thenReturn(saved);

        List<RateScreenService.CityClassRow> rows = service.cityClasses(context);

        assertThat(rows)
                .extracting(RateScreenService.CityClassRow::cityClass)
                .containsExactly("X", "Y", "Z", "DOUBLE");
        assertThat(rows.get(0).hraPercent()).isEqualByComparingTo("30");
        assertThat(rows.get(0).hraCdaPercent()).isEqualByComparingTo("28");
        assertThat(rows.get(0).ccaAmount()).isEqualByComparingTo("1800");
        assertThat(rows.get(0).inForce()).isTrue();
        assertThat(rows.get(0).pending()).isTrue();
        assertThat(rows.get(1).inForce()).isFalse();
        assertThat(rows.get(3).inForce()).isTrue();
    }

    @Test
    void SRS_1_3_withNothingInForceEveryClassIsNotSeeded() {
        List<RateScreenService.CityClassRow> rows = service.cityClasses(context);
        assertThat(rows).allMatch(r -> !r.inForce() && !r.pending());
        verify(lines, never()).findByRateSetIdIn(anyList());
    }

    private static RateMasterView master(String code, String name, String group) {
        return new RateMasterView(code, name, group, null, null, null);
    }

    @Test
    void SRS_11_15_theFiltersKeepThePatternTheGroupAndTheSearch() {
        List<RateMasterView> all =
                List.of(
                        master("HILL_IDA", "Hill Compensatory - IDA", "SPECIAL_COMPENSATORY"),
                        master("HILL_CDA", "Hill Compensatory - CDA", "SPECIAL_COMPENSATORY"),
                        master("GIS_IDA", "Group Insurance - IDA", "OTHER_RATE_MASTER"));
        assertThat(RateScreenService.filter(all, null, null, null)).hasSize(3);
        assertThat(RateScreenService.filter(all, "", "", "  ")).hasSize(3);
        assertThat(RateScreenService.filter(all, "IDA", null, null)).hasSize(2);
        assertThat(RateScreenService.filter(all, "CDA", "OTHER_RATE_MASTER", null)).isEmpty();
        assertThat(RateScreenService.filter(all, null, "OTHER_RATE_MASTER", null)).hasSize(1);
        assertThat(RateScreenService.filter(all, "IDA", null, " hill ")).hasSize(1);
    }

    @Test
    void SRS_G_13_theM22ExportWritesTheFilteredListAndChecksItsPermission() throws IOException {
        RateMasterService.VersionView inForce =
                new RateMasterService.VersionView(
                        1L, 3, TODAY, null, "APPROVED", 9L, null, List.of());
        when(rates.list(context, Screen.SPECIAL))
                .thenReturn(
                        List.of(
                                new RateMasterView("HILL_IDA", "Hill", "G", 163, inForce, null),
                                master("HILL_CDA", "Hill", "G")));
        List<Object[]> written = new ArrayList<>();
        ExportSink sink =
                new ExportSink() {
                    @Override
                    public void header(String... titles) {}

                    @Override
                    public void row(Object... cells) {
                        written.add(cells);
                    }

                    @Override
                    public long rows() {
                        return written.size();
                    }

                    @Override
                    public void close() {}
                };

        assertThat(service.specialExportSize(context, "IDA", null, null)).isEqualTo(1);
        service.exportSpecial(context, null, null, null, sink);

        assertThat(written).hasSize(2);
        assertThat(written.get(0)[3]).isEqualTo(3);
        assertThat(written.get(1)[3]).isNull();
        verify(policy, org.mockito.Mockito.times(2)).require(context, "M-22", "EXPORT");
    }

    @Test
    void wrongRole_theExportIsRefusedBeforeAnyRowIsRead() {
        doThrow(new AccessDeniedException("no")).when(policy).require(context, "M-22", "EXPORT");
        assertThatThrownBy(() -> service.specialExportSize(context, null, null, null))
                .isInstanceOf(AccessDeniedException.class);
        verify(rates, never()).list(any(), any());
    }

    @Test
    void SRS_G_30_anOrderIsStoredWithAnM04VersionOnly() {
        RateSet set = version(5, "DA_IDA", 1, TODAY, null, true);
        when(sets.findByRateSetId(5L)).thenReturn(Optional.of(set));
        when(rates.screenOf("DA_IDA")).thenReturn(Optional.of(Screen.STAFF));

        service.orders(context, 5L);
        service.attachOrder(context, 5L, "CIRCULAR", "R", TODAY, "HQ", "o.pdf", new byte[] {1});
        service.file(context, "1.1.1.1", 5L, 3L);

        verify(documents).active("rate_set", "5");
        verify(documents).attach(any(), any());
        verify(documents).read(any(), eq("1.1.1.1"), eq("rate_set"), eq("5"), eq(3L));
        verify(policy).require(context, "M-04", "CREATE");
    }

    @Test
    void SRS_G_30_aVersionOfAnotherScreenOrNoVersionIsNotInTheCatalogue() {
        RateSet set = version(6, "HILL_IDA", 1, TODAY, null, true);
        when(sets.findByRateSetId(6L)).thenReturn(Optional.of(set));
        when(sets.findByRateSetId(7L)).thenReturn(Optional.empty());
        when(rates.screenOf("HILL_IDA")).thenReturn(Optional.of(Screen.SPECIAL));

        for (long id : new long[] {6L, 7L}) {
            assertThatThrownBy(() -> service.orders(context, id))
                    .isInstanceOfSatisfying(
                            FciException.class,
                            e ->
                                    assertThat(e.getErrorCode())
                                            .isEqualTo(ApiErrorCode.MASTER_NOT_IN_CATALOGUE));
        }
        verify(documents, never()).active(any(), any());
    }
}
