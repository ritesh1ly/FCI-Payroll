package in.gov.fci.payroll.platform.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;
import org.springframework.http.ResponseEntity;

class ResultSummarizerTest {

    private final ResultSummarizer summarizer = new ResultSummarizer();

    record UserDto(String id, String email) implements Identifiable {}

    @Test
    void summarisesWithoutPayload() {
        assertThat(summarizer.summarize(null)).isEqualTo("{type=null}");
        assertThat(summarizer.summarize(new ArrayList<>(List.of(1, 2))))
                .isEqualTo("{type=ArrayList, size=2}");
        assertThat(summarizer.summarize(new HashMap<>(Map.of("a", 1))))
                .isEqualTo("{type=HashMap, size=1}");
        assertThat(summarizer.summarize(new String[3])).isEqualTo("{type=String[], length=3}");
        assertThat(summarizer.summarize(true)).isEqualTo("{type=Boolean, value=true}");
        assertThat(summarizer.summarize(42L)).isEqualTo("{type=Long, value=42}");
        assertThat(summarizer.summarize("secret text")).isEqualTo("{type=String}");
    }

    @Test
    void identifiableResultsShowOnlyTheId() {
        assertThat(summarizer.summarize(new UserDto("u-9", "someone@fci.gov.in")))
                .isEqualTo("{type=UserDto, id=u-9}")
                .doesNotContain("someone");
    }

    @Test
    void unwrapsResponseEntityAndOptional() {
        assertThat(
                        summarizer.summarize(
                                ResponseEntity.status(201).body(new UserDto("u-1", "x@y.in"))))
                .isEqualTo("{type=ResponseEntity, status=201, body={type=UserDto, id=u-1}}");
        assertThat(summarizer.summarize(Optional.empty())).isEqualTo("{type=Optional, empty=true}");
        assertThat(summarizer.summarize(Optional.of(List.of())))
                .isEqualTo("{type=Optional, value={type=ListN, size=0}}");
    }

    @Test
    void summarisesSpringDataPagesAndSlices() {
        var page = new PageImpl<>(List.of("a", "b"), PageRequest.of(1, 2), 9);
        assertThat(summarizer.summarize(page)).isEqualTo("{type=Page, page=1, size=2, total=9}");
        var slice = new SliceImpl<>(List.of("a"), PageRequest.of(0, 5), true);
        assertThat(summarizer.summarize(slice)).isEqualTo("{type=SliceImpl, page=0, size=1}");
    }
}
