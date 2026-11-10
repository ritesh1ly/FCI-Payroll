package in.gov.fci.payroll.employee.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * The M-01 allowance flags at their edges (SRS-1-11, BR-EMPM-004): a flag is "since" its latest
 * version in force today, a version of another flag or one dated later not counting; a version
 * migrated without its change time shows none; a separated member gets no new flag.
 */
class EmployeeFlagServiceBranchGapsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 26);

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final DocumentStore documents = mock(DocumentStore.class);
    private final EmployeeFlagService service =
            new EmployeeFlagService(
                    db.jdbc,
                    mock(Authorization.class),
                    approvals,
                    documents,
                    new ObjectMapper(),
                    Clock.fixed(Instant.parse("2026-10-26T06:00:00Z"), ZoneOffset.UTC));

    private static AccessContext hr() {
        return new AccessContext(
                4L, "hr@x", "HR", "s", "P", 300L, null, List.of(), Map.of(), Set.of(), Set.of(),
                Set.of());
    }

    private void member(String status) {
        db.rows(
                "FROM employee WHERE emp_num",
                row(
                        "employee_id",
                        7L,
                        "emp_num",
                        "100041",
                        "location_id",
                        300L,
                        "comp_joining_date",
                        Date.valueOf("2010-07-01"),
                        "employment_status",
                        status));
        db.rows(
                "list_code = 'ELIGIBILITY_FLAG'",
                row("value_code", "CCA", "value_label_en", "City Compensatory Allowance"));
    }

    @Test
    void SRS_1_11_aFlagIsSinceItsLatestVersionInForceNotAnotherFlagsNorALaterOne() {
        member("IN_SERVICE");
        db.rows(
                "effective_to FROM employee_eligibility_flag",
                row(
                        "flag_code",
                        "CCA",
                        "flag_value",
                        "Y",
                        "effective_from",
                        Date.valueOf("2026-04-01"),
                        "effective_to",
                        null),
                row(
                        "flag_code",
                        "CCA",
                        "flag_value",
                        "N",
                        "effective_from",
                        Date.valueOf("2026-12-01"),
                        "effective_to",
                        null),
                row(
                        "flag_code",
                        "NPA",
                        "flag_value",
                        "Y",
                        "effective_from",
                        Date.valueOf("2026-09-01"),
                        "effective_to",
                        null));
        EmployeeFlagService.FlagState cca = service.flags(hr(), "100041").flags().getFirst();
        assertThat(cca.code()).isEqualTo("CCA");
        assertThat(cca.value()).isEqualTo("Y");
        assertThat(cca.since()).isEqualTo(LocalDate.of(2026, 4, 1)).isBefore(TODAY);
        assertThat(cca.pending()).isFalse();
    }

    @Test
    void BR_EMPM_004_aVersionWithoutItsChangeTimeShowsNone() {
        member("IN_SERVICE");
        db.value("SELECT COUNT(*) FROM employee_eligibility_flag", 2L);
        Instant changed = Instant.parse("2026-10-01T05:00:00Z");
        db.rows(
                "changed_at, approval_id FROM",
                row(
                        "flag_code",
                        "CCA",
                        "flag_value",
                        "Y",
                        "effective_from",
                        Date.valueOf("2026-10-01"),
                        "effective_to",
                        null,
                        "reason",
                        "Order 12",
                        "changed_by",
                        "checker",
                        "changed_at",
                        Timestamp.from(changed),
                        "approval_id",
                        41L),
                row(
                        "flag_code",
                        "CCA",
                        "flag_value",
                        "N",
                        "effective_from",
                        Date.valueOf("2020-04-01"),
                        "effective_to",
                        Date.valueOf("2026-09-30"),
                        "reason",
                        "Migrated",
                        "changed_by",
                        "migration",
                        "changed_at",
                        null,
                        "approval_id",
                        null));
        List<EmployeeFlagService.FlagVersion> rows =
                service.history(hr(), "100041", new PageSpec(0, 20)).items();
        assertThat(rows)
                .extracting(EmployeeFlagService.FlagVersion::changedAt)
                .containsExactly(changed, null);
        assertThat(rows.get(1).effectiveTo()).isEqualTo(LocalDate.of(2026, 9, 30));
    }

    @Test
    void SRS_1_11_aSeparatedMemberGetsNoNewFlag() {
        member("SEPARATED");
        assertThatThrownBy(
                        () ->
                                service.propose(
                                        hr(),
                                        "100041",
                                        new EmployeeFlagService.Proposal(
                                                "CCA", "N", TODAY, "Order 12"),
                                        new EmployeeFlagService.Document(
                                                "OTHER",
                                                null,
                                                TODAY,
                                                "Establishment",
                                                "order.pdf",
                                                new byte[] {1})))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> {
                            assertThat(e.getErrorCode()).isEqualTo(ApiErrorCode.M01_FLAG);
                            assertThat(e.getMessageArgs()).containsExactly("SEPARATED");
                        });
        verifyNoInteractions(approvals, documents);
    }
}
