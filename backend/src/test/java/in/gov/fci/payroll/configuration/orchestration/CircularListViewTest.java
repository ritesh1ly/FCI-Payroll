package in.gov.fci.payroll.configuration.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.ReferenceData;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.configuration.business.model.CircularOrder;
import in.gov.fci.payroll.configuration.business.model.CircularOrderLink;
import in.gov.fci.payroll.configuration.business.model.OperationMaster;
import in.gov.fci.payroll.configuration.business.model.RateSet;
import in.gov.fci.payroll.configuration.persistence.ConfigurationRepositories;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The M-25 register as Wireframes v6.1 draws it (SRS M-25 fields Where Used, actions Export): the
 * count of each row without a query per row, the numbers another list shows, and the export with
 * the code and the label of each coded value (SRS-G-39).
 */
class CircularListViewTest {

    private final ConfigurationRepositories.Circulars circulars =
            mock(ConfigurationRepositories.Circulars.class);
    private final ConfigurationRepositories.CircularLinks links =
            mock(ConfigurationRepositories.CircularLinks.class);
    private final ConfigurationRepositories.Operations operations =
            mock(ConfigurationRepositories.Operations.class);
    private final ConfigurationRepositories.RateSets rateSets =
            mock(ConfigurationRepositories.RateSets.class);
    private final ReferenceData reference = mock(ReferenceData.class);
    private final Authorization policy = mock(Authorization.class);
    private final AccessContext context = mock(AccessContext.class);
    private final CircularService service =
            new CircularService(
                    circulars,
                    mock(ConfigurationRepositories.CircularScopes.class),
                    links,
                    operations,
                    rateSets,
                    reference,
                    mock(OrgDirectory.class),
                    mock(DocumentStore.class),
                    mock(MakerChecker.class),
                    policy,
                    mock(SystemParameters.class),
                    mock(AuditService.class),
                    Clock.systemUTC());

    /** Collects the rows of an export. */
    private static final class Rows implements ExportSink {
        final List<List<Object>> rows = new ArrayList<>();
        String[] header;

        @Override
        public void header(String... titles) {
            header = titles;
        }

        @Override
        public void row(Object... cells) {
            rows.add(Arrays.asList(cells));
        }

        @Override
        public long rows() {
            return rows.size();
        }

        @Override
        public void close() {
            // Nothing to complete.
        }
    }

    private CircularOrder circular(long id, String number, String authority, String category) {
        CircularOrder c = mock(CircularOrder.class);
        when(c.getCircularOrderId()).thenReturn(id);
        when(c.getCircularNumber()).thenReturn(number);
        when(c.getIssuingAuthorityCode()).thenReturn(authority);
        when(c.getCategoryCode()).thenReturn(category);
        when(c.getCircularStatus()).thenReturn("ACTIVE");
        when(c.getCircularDate()).thenReturn(LocalDate.of(2026, 7, 4));
        return c;
    }

    private void search(CircularOrder... rows) {
        when(circulars.search(any(), any(), any(), any(), any())).thenReturn(List.of(rows));
    }

    private static CircularOrderLink link(long circular, String entity, String key) {
        CircularOrderLink l = mock(CircularOrderLink.class);
        when(l.getCircularOrderId()).thenReturn(circular);
        when(l.getLinkedEntity()).thenReturn(entity);
        when(l.getLinkedKey()).thenReturn(key);
        return l;
    }

    private static OperationMaster operation(long circular, String code) {
        OperationMaster o = mock(OperationMaster.class);
        when(o.getCircularOrderId()).thenReturn(circular);
        when(o.getOperationCode()).thenReturn(code);
        return o;
    }

    private static RateSet rates(long circular) {
        RateSet r = mock(RateSet.class);
        when(r.getCircularOrderId()).thenReturn(circular);
        return r;
    }

    @Test
    void SRS_G_38_theCountOfEachRowIsWhatWhereUsedLists_anOperationLinkedTwiceCountsOnce() {
        CircularOrder a = circular(1L, "EP-01/2026/DA-07", "HQ_PERSONNEL", "DA");
        CircularOrder b = circular(2L, "IR(L)/12/2026/NWNP", "IR_L", "WAGE");
        CircularOrder unused = circular(3L, "RO-MH/IR/2026/14", "IR_L", "WAGE");
        search(a, b, unused);
        CircularOrderLink operationLink = link(1L, OperationService.ENTITY, "W2P");
        CircularOrderLink ruleLink = link(1L, "rule_version", "15");
        when(links.findByCircularOrderIdIn(List.of(1L, 2L, 3L)))
                .thenReturn(List.of(operationLink, ruleLink));
        OperationMaster linked = operation(1L, "W2P");
        OperationMaster own = operation(2L, "W2P");
        when(operations.findByCircularOrderIdIn(List.of(1L, 2L, 3L)))
                .thenReturn(List.of(linked, own));
        var rateSet = rates(1L);
        when(rateSets.findByCircularOrderIdIn(List.of(1L, 2L, 3L))).thenReturn(List.of(rateSet));

        assertThat(
                        service.search(context, null).stream()
                                .map(CircularService.CircularView::whereUsed))
                .containsExactly(3, 1, 0);
        verify(links, never()).findByCircularOrderIdOrderByLinkedAtDesc(any());
    }

    @Test
    void SRS_G_41_numbersReadsTheGivenCirculars_noIdsNoQuery() {
        assertThat(service.numbers(List.of())).isEmpty();
        verify(circulars, never()).findByCircularOrderIdIn(any());

        CircularOrder a = circular(1L, "IR(L)/4/2026", "IR_L", "NORM");
        when(circulars.findByCircularOrderIdIn(List.of(1L, 9L))).thenReturn(List.of(a));
        assertThat(service.numbers(List.of(1L, 9L))).isEqualTo(Map.of(1L, "IR(L)/4/2026"));
    }

    @Test
    void SRS_G_39_theExportCarriesCodeAndLabel_anUnknownCodeLeavesTheLabelEmpty()
            throws IOException {
        CircularOrder c = circular(1L, "EP-01/2026/DA-07", "HQ_PERSONNEL", "RETIRED_CODE");
        when(c.isAppliesAllOffices()).thenReturn(true);
        when(c.getDocumentAttachmentId()).thenReturn(5L);
        search(c);
        when(reference.list(CircularService.AUTHORITY))
                .thenReturn(
                        Optional.of(
                                new ReferenceData.ListView(
                                        CircularService.AUTHORITY,
                                        "Issuing authority",
                                        "MASTERS",
                                        false,
                                        List.of(
                                                new ReferenceData.Value(
                                                        "HQ_PERSONNEL",
                                                        "HQ Personnel",
                                                        null,
                                                        1,
                                                        null,
                                                        null,
                                                        true)))));
        when(reference.list(CircularService.CATEGORY)).thenReturn(Optional.empty());

        Rows sink = new Rows();
        service.export(
                context, new CircularService.SearchQuery(null, null, null, null, null), sink);

        assertThat(sink.header).contains("issuing_authority", "issuing_authority_label");
        List<Object> row = sink.rows.get(0);
        assertThat(row.get(0)).isEqualTo("EP-01/2026/DA-07");
        assertThat(row.get(2)).isEqualTo("HQ_PERSONNEL");
        assertThat(row.get(3)).isEqualTo("HQ Personnel");
        assertThat(row.get(5)).isEqualTo("RETIRED_CODE");
        assertThat(row.get(6)).isNull();
        assertThat(row.get(8)).isEqualTo("ALL");
        assertThat(row.get(12)).isEqualTo("Y");
        verify(policy).require(context, CircularService.SCREEN, "EXPORT");
    }

    @Test
    void SRS_G_38_theExportSizeIsTheRowsOfTheSearch() {
        search(circular(1L, "A/1", "HQ_PERSONNEL", "DA"), circular(2L, "A/2", "IR_L", "DA"));
        assertThat(
                        service.exportSize(
                                context,
                                new CircularService.SearchQuery(null, "DA", null, null, null)))
                .isEqualTo(2);
        verify(policy).require(context, CircularService.SCREEN, "EXPORT");
    }
}
