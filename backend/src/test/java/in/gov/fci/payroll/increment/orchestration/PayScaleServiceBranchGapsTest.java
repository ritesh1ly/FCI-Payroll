package in.gov.fci.payroll.increment.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.cols;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.Circulars;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * M-03 (SRS l.3952, SC-17-0036): every version of a scale, newest first; the version in force has
 * no Effective To, a superseded one shows the day it ended (end-dated, never deleted).
 */
class PayScaleServiceBranchGapsTest {

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final PayScaleService service =
            new PayScaleService(
                    db.named,
                    mock(Authorization.class),
                    mock(MakerChecker.class),
                    mock(Circulars.class),
                    mock(AuditService.class));
    private final AccessContext hq =
            new AccessContext(
                    1L, "ra@x", "R", "s", "P", 1L, null, List.of(), Map.of(), Set.of(), Set.of(),
                    Set.of());

    @Test
    void SC_17_0036_theVersionInForceHasNoEndAndASupersededOneShowsItsEnd() {
        db.rows(
                "FROM pay_scale_band_version v",
                cols(
                        2,
                        new BigDecimal("60000"),
                        new BigDecimal("64300"),
                        new BigDecimal("1500"),
                        Date.valueOf("2026-04-01"),
                        null),
                cols(
                        1,
                        new BigDecimal("58000"),
                        new BigDecimal("62000"),
                        null,
                        Date.valueOf("2025-04-01"),
                        Date.valueOf("2026-03-31")));

        List<PayScaleService.Version> versions = service.versions(hq, "E-4");

        assertThat(versions)
                .extracting(
                        PayScaleService.Version::versionNo, PayScaleService.Version::effectiveTo)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(2, null),
                        org.assertj.core.groups.Tuple.tuple(1, LocalDate.of(2026, 3, 31)));
    }
}
