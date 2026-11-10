package in.gov.fci.payroll.platform.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LikesTest {

    @Test
    void escapesWildcardsAndTheEscapeCharacter() {
        assertThat(Likes.escape("/HQ/ZO-N/")).isEqualTo("/HQ/ZO-N/");
        assertThat(Likes.escape("50%_off\\")).isEqualTo("50\\%\\_off\\\\");
    }
}
