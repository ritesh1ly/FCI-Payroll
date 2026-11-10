package in.gov.fci.payroll.integration.business;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.integration.api.HrmsApi;
import in.gov.fci.payroll.integration.business.HrmsContracts.Kind;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * SRS-INT-1, SRS-INT-23, SRS-INT-35: every API of the collection has exactly one kind and contract.
 */
class HrmsContractsTest {

    @Test
    void SRS_INT_1_everyApiHasOneKindAndItsContract() {
        for (HrmsApi api : HrmsApi.values()) {
            Kind kind = HrmsContracts.kind(api);
            switch (kind) {
                case TOKEN -> assertThat(api).isEqualTo(HrmsApi.TOKEN);
                case INTAKE -> {
                    assertThat(api.method()).isEqualTo("POST");
                    assertThat(HrmsContracts.request(api)).as(api.code()).isPresent();
                    assertThat(HrmsContracts.lookup(api)).isEmpty();
                }
                case STATUS -> {
                    assertThat(api.method()).isEqualTo("GET");
                    assertThat(HrmsContracts.lookup(api)).as(api.code()).isPresent();
                }
                case CANCEL -> {
                    assertThat(api.method()).isEqualTo("PUT");
                    assertThat(HrmsContracts.lookup(api)).as(api.code()).isPresent();
                }
                case REPORT -> {
                    assertThat(api.method()).isEqualTo("GET");
                    assertThat(HrmsContracts.request(api)).isEmpty();
                    assertThat(HrmsContracts.lookup(api)).isEmpty();
                }
            }
        }
    }

    @Test
    void SRS_INT_23_theCountsMatchTheSelection() {
        var kinds =
                Arrays.stream(HrmsApi.values())
                        .collect(Collectors.groupingBy(HrmsContracts::kind, Collectors.counting()));
        // 16 class A + 4 class B request APIs (cafeteria, lease, liveries, buy-back).
        assertThat(kinds.get(Kind.INTAKE)).isEqualTo(20);
        assertThat(kinds.get(Kind.CANCEL)).isEqualTo(14);
        // Payslip, forecast, CPF slip, control register + the two employee reads (PENDING
        // API-16-7).
        assertThat(kinds.get(Kind.REPORT)).isEqualTo(6);
        assertThat(kinds.get(Kind.TOKEN)).isEqualTo(1);
        assertThat(kinds.get(Kind.STATUS)).isEqualTo(16);
    }

    @Test
    void SRS_INT_12_theStagingTypeIsUniquePerRequestApi() {
        var types =
                Arrays.stream(HrmsApi.values())
                        .map(HrmsContracts::request)
                        .flatMap(java.util.Optional::stream)
                        .map(HrmsContracts.Request::txnType)
                        .toList();
        assertThat(types).doesNotHaveDuplicates();
        for (String type : types) {
            assertThat(HrmsContracts.byTxnType(type)).isPresent();
        }
        assertThat(HrmsContracts.byTxnType("NOPE")).isEmpty();
    }

    @Test
    void SRS_INT_35_everyStatusAndCancelTypeIsAStagedRequestTypeOrLeaveInfo() {
        for (HrmsApi api : HrmsApi.values()) {
            HrmsContracts.lookup(api)
                    .ifPresent(
                            l ->
                                    l.txnTypes()
                                            .forEach(
                                                    t ->
                                                            assertThat(
                                                                            HrmsContracts.byTxnType(
                                                                                                    t)
                                                                                            .isPresent()
                                                                                    || t.equals(
                                                                                            "LEAVE_INFO"))
                                                                    .as("%s %s", api.code(), t)
                                                                    .isTrue()));
        }
    }

    @Test
    void SRS_INT_23_theRequestPayloadsAreTheCollectionsAndCarryTheKeys() {
        for (HrmsApi api : HrmsApi.values()) {
            HrmsContracts.request(api)
                    .ifPresent(
                            c -> {
                                assertThat(c.fields()).contains("request_id");
                                if (!c.employeeInQuery()) {
                                    assertThat(c.fields()).contains("emp_num");
                                }
                                if (api != HrmsApi.UPDATING_HRA_DA) {
                                    assertThat(c.fields()).contains("attribute1");
                                }
                            });
        }
    }
}
