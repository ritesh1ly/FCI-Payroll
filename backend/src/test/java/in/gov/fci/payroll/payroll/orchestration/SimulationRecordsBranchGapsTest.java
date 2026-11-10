package in.gov.fci.payroll.payroll.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.payroll.business.SimulationRules;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.PreparedStatementSetter;
import tools.jackson.databind.json.JsonMapper;

/**
 * SRS-15-27 (plan 05j): the background simulation of an abandoned C-13 request is FAILED on its
 * record whether it was still queued or already running; a finished one, or one of another request,
 * is left as it is.
 */
class SimulationRecordsBranchGapsTest {

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final JsonMapper json = JsonMapper.builder().build();
    private final SimulationRecords records =
            new SimulationRecords(
                    db.jdbc,
                    json,
                    Clock.fixed(Instant.parse("2026-10-28T06:00:00Z"), ZoneOffset.UTC));

    private Map<String, Object> record(long id, SimulationRecords.Status status, Long request) {
        SimulationRecords.Doc doc =
                new SimulationRecords.Doc(
                        status,
                        SimulationRules.Mode.BACKGROUND,
                        "2026-10",
                        40L,
                        "DO-DEL",
                        SimulationRules.Population.OFFICE,
                        null,
                        SimulationRules.WhatIf.AS_IS,
                        null,
                        null,
                        request,
                        3,
                        null,
                        null,
                        null,
                        null);
        return row(
                "simulation_id",
                id,
                "requested_by",
                "maker@x",
                "requested_at",
                Timestamp.from(Instant.parse("2026-10-28T05:00:00Z")),
                "population_scope",
                "C09;202610;DO-DEL;OFFICE;900;AS_IS",
                "population_count",
                900L,
                "input_hash",
                SimulationRecords.NOT_YET,
                "impact_summary",
                json.writeValueAsString(doc));
    }

    @Test
    void SRS_15_27_aQueuedOrRunningSimulationOfTheAbandonedRequestIsFailed() throws Exception {
        db.rows(
                "FROM rule_simulation WHERE population_scope LIKE 'C09;%' AND input_hash = ?",
                record(1L, SimulationRecords.Status.QUEUED, 9L),
                record(2L, SimulationRecords.Status.RUNNING, 9L),
                record(3L, SimulationRecords.Status.COMPLETE, 9L),
                record(4L, SimulationRecords.Status.FAILED, 9L),
                record(5L, SimulationRecords.Status.RUNNING, 8L));
        assertThat(records.abandoned(9L)).isEqualTo(2);
        List<ScriptedJdbc.Update> updates = db.updates("UPDATE rule_simulation SET impact_summary");
        assertThat(updates).hasSize(2);
        for (int i = 0; i < 2; i++) {
            PreparedStatement ps = mock(PreparedStatement.class);
            ((PreparedStatementSetter) updates.get(i).args().get(0)).setValues(ps);
            verify(ps).setLong(3, i + 1L);
        }
    }
}
