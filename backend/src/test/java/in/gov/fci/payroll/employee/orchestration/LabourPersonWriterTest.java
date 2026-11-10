package in.gov.fci.payroll.employee.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.employee.api.LabourPersons.Person;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * The labour member's person record (SRS-2-27, SRS-2-30; plan 20 D20-1): a casual labourer without
 * a CPF code or retirement date is written with fund type NONE, no retirement date and the casual
 * flag; a regular one with a CPF code is CPF with the date; a correction may clear the date.
 */
class LabourPersonWriterTest {

    private static final LocalDate BORN = LocalDate.of(1990, 6, 15);
    private static final LocalDate RETIRES = LocalDate.of(2050, 6, 30);

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final LabourPersonWriter writer =
            new LabourPersonWriter(db.jdbc, mock(ApplicationEventPublisher.class));

    private static Person person(String cpf, LocalDate retires, boolean casual) {
        return new Person(
                "L0001",
                "DEPT_LABOUR",
                "Ramu",
                null,
                "Shyam",
                "M",
                BORN,
                LocalDate.of(2015, 4, 1),
                3L,
                40L,
                41L,
                4L,
                null,
                cpf,
                null,
                null,
                null,
                null,
                retires,
                casual);
    }

    @Test
    void SRS_2_27_aCasualLabourerWithoutCpfOrRetirementAndARegularOneWithBoth() {
        assertThat(writer.create(person(null, null, true), "maker")).isEqualTo(1000L);
        assertThat(writer.create(person("DL/1234", RETIRES, false), "maker")).isEqualTo(1001L);
        List<ScriptedJdbc.Update> created = db.updates("INSERT INTO employee");
        assertThat(created.get(0).args()).endsWith(null, "Y");
        assertThat(created.get(0).args()).contains("NONE");
        assertThat(created.get(1).args()).endsWith(Date.valueOf(RETIRES), "N");
        assertThat(created.get(1).args()).contains("CPF");

        writer.amend(7L, person(null, null, true), "maker");
        writer.amend(7L, person("DL/1234", RETIRES, false), "maker");
        List<ScriptedJdbc.Update> amended = db.updates("UPDATE employee SET first_name = ?, last");
        assertThat(amended.get(0).args()).endsWith(null, "Y", 7L).contains("NONE");
        assertThat(amended.get(1).args()).endsWith(Date.valueOf(RETIRES), "N", 7L).contains("CPF");
    }

    @Test
    void SRS_2_30_aCorrectionMayClearTheRetirementDate() {
        writer.correct(7L, null, null, BORN, null);
        writer.correct(7L, null, null, BORN, RETIRES);
        List<ScriptedJdbc.Update> corrected = db.updates("SET birth_date = ?, retirement_date");
        assertThat(corrected.get(0).args()).containsExactly(Date.valueOf(BORN), null, 7L);
        assertThat(corrected.get(1).args())
                .containsExactly(Date.valueOf(BORN), Date.valueOf(RETIRES), 7L);
    }
}
