package in.gov.fci.payroll.increment.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * The drawn order card of M-03 (SRS-G-30, A-41, D17-10): the copies of a scale are read under M-03
 * VIEW and stored under M-03 CREATE at Headquarters, on the scale named; an unknown scale is a 404
 * and a role without the right reaches neither the scale nor the store.
 */
class PayScaleOrdersTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));

    private final NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    private final Authorization policy = mock(Authorization.class);
    private final DocumentStore documents = mock(DocumentStore.class);
    private final PayScaleOrders orders = new PayScaleOrders(named, policy, documents);
    private final AccessContext context =
            new AccessContext(
                    1L, "ra@x", "R", "s", "P", 1L, null, List.of(), Map.of(), Set.of(), Set.of(),
                    Set.of());

    private void scaleExists(long count) {
        when(named.queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(count);
    }

    private static DocumentStore.NewAttachment order() {
        return new DocumentStore.NewAttachment(
                "ignored",
                "ignored",
                "CIRCULAR",
                "FCI/EST/SCALE/E-4",
                TODAY,
                "Headquarters",
                "order.pdf",
                new byte[] {1},
                true);
    }

    @Test
    void SRS_G_30_theCopiesOfAScaleAreReadUnderM03View() {
        scaleExists(1);
        orders.orders(context, "E-4");
        verify(policy).require(context, "M-03", "VIEW");
        verify(documents).active("pay_scale_band", "E-4");
    }

    @Test
    void SRS_G_30_anUnknownScaleIsNotFoundOnEveryPath() {
        scaleExists(0);
        when(policy.isAtHeadquarters(context)).thenReturn(true);
        assertThatThrownBy(() -> orders.orders(context, "NONE"))
                .isInstanceOf(FciException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.NOT_FOUND);
        assertThatThrownBy(() -> orders.attach(context, "NONE", order()))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> orders.file(context, "10.0.0.1", "NONE", 7L))
                .isInstanceOf(FciException.class);
        verifyNoInteractions(documents);
    }

    @Test
    void D17_10_aCopyIsStoredOnTheScaleNamedNeverOnTheCallersRecord() {
        scaleExists(1);
        when(policy.isAtHeadquarters(context)).thenReturn(true);
        orders.attach(context, "E-4", order());
        verify(policy).require(context, "M-03", "CREATE");
        ArgumentCaptor<DocumentStore.NewAttachment> stored =
                ArgumentCaptor.forClass(DocumentStore.NewAttachment.class);
        verify(documents).attach(eq("ra@x"), stored.capture());
        assertThat(stored.getValue().entityName()).isEqualTo("pay_scale_band");
        assertThat(stored.getValue().entityKey()).isEqualTo("E-4");
        assertThat(stored.getValue().containsPii()).isFalse();
    }

    @Test
    void D17_10_awayFromHeadquartersNothingIsStored() {
        when(policy.isAtHeadquarters(context)).thenReturn(false);
        assertThatThrownBy(() -> orders.attach(context, "E-4", order()))
                .isInstanceOf(FciException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.ACCESS_DENIED);
        verifyNoInteractions(documents);
    }

    @Test
    void R_RBAC_aRoleWithoutM03CreateReachesNeitherTheScaleNorTheStore() {
        doThrow(new FciException(CommonErrorCode.ACCESS_DENIED))
                .when(policy)
                .require(context, "M-03", "CREATE");
        assertThatThrownBy(() -> orders.attach(context, "E-4", order()))
                .isInstanceOf(FciException.class);
        verifyNoInteractions(named, documents);
    }

    @Test
    void SRS_G_30_aFileIsReadAsAFileOfThatScaleUnderM03View() {
        scaleExists(1);
        orders.file(context, "10.0.0.1", "E-4", 7L);
        verify(policy).require(context, "M-03", "VIEW");
        verify(documents).read("ra@x", "10.0.0.1", "pay_scale_band", "E-4", 7L);
    }

    @Test
    void R_RBAC_aRoleWithoutM03ViewReadsNothing() {
        doThrow(new FciException(CommonErrorCode.ACCESS_DENIED))
                .when(policy)
                .require(context, "M-03", "VIEW");
        assertThatThrownBy(() -> orders.orders(context, "E-4")).isInstanceOf(FciException.class);
        assertThatThrownBy(() -> orders.file(context, "10.0.0.1", "E-4", 7L))
                .isInstanceOf(FciException.class);
        verifyNoInteractions(named, documents);
    }
}
