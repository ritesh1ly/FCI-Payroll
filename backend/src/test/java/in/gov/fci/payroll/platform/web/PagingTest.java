package in.gov.fci.payroll.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.paging.PageSizes;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/** docs/plan/X-pagination.md (owner requirement 2026-10-24): the page request of every list. */
class PagingTest {

    private static final PageSizes SIZES =
            new PageSizes() {
                @Override
                public List<Integer> offered() {
                    return List.of(10, 20, 50, 100);
                }

                @Override
                public int defaultSize() {
                    return 20;
                }
            };

    private final Paging paging = new Paging(SIZES);

    @Test
    void SRS_NF_3_missingMeansTheFirstPageAtTheDefaultSize() {
        assertThat(paging.resolve(null, null)).isEqualTo(new PageSpec(0, 20));
        assertThat(paging.resolve(3, 100)).isEqualTo(new PageSpec(3, 100));
        assertThat(paging.resolve(0, 10)).isEqualTo(new PageSpec(0, 10));
    }

    @Test
    void SRS_NF_3_aSizeNotOfferedOrANegativePageIsRefusedNeverClamped() {
        for (Integer[] bad : new Integer[][] {{0, 25}, {0, 101}, {0, 0}, {0, 100000}, {-1, 20}}) {
            assertThatThrownBy(() -> paging.resolve(bad[0], bad[1]))
                    .isInstanceOfSatisfying(
                            FciException.class,
                            e ->
                                    assertThat(e.getErrorCode())
                                            .isEqualTo(CommonErrorCode.PAGE_REQUEST));
        }
    }

    @Test
    void SRS_NF_3_aSliceHasTheRowsOfItsPageAndTheTrueTotal() {
        List<Integer> rows = IntStream.rangeClosed(1, 45).boxed().toList();
        Paged<Integer> second = Paged.slice(rows, new PageSpec(1, 20));
        assertThat(second.items())
                .containsExactlyElementsOf(IntStream.rangeClosed(21, 40).boxed().toList());
        assertThat(second.total()).isEqualTo(45);
        assertThat(second.page()).isEqualTo(1);
        assertThat(second.size()).isEqualTo(20);
        assertThat(Paged.slice(rows, new PageSpec(2, 20)).items())
                .containsExactly(41, 42, 43, 44, 45);
        // Past the end: no rows, the true total, so the screen can move back.
        Paged<Integer> past = Paged.slice(rows, new PageSpec(9, 20));
        assertThat(past.items()).isEmpty();
        assertThat(past.total()).isEqualTo(45);
        assertThat(Paged.slice(List.of(), new PageSpec(0, 10)).total()).isZero();
        // A page so far out that page x size overflows an int still answers empty.
        assertThat(Paged.slice(rows, new PageSpec(Integer.MAX_VALUE, 100)).items()).isEmpty();
    }

    @Test
    void SRS_NF_3_aDatabasePageAndAConvertedPageKeepTheirNumbers() {
        Paged<String> page =
                Paged.of(new PageImpl<>(List.of("a", "b"), PageRequest.of(2, 10), 22))
                        .map(String::toUpperCase);
        assertThat(page).isEqualTo(new Paged<>(List.of("A", "B"), 2, 10, 22));
        assertThat(new PageSpec(2, 50).pageable(Sort.by("id")).getOffset()).isEqualTo(100);
        assertThat(new PageSpec(2, 50).pageable(Sort.by("id")).getSort()).isEqualTo(Sort.by("id"));
        assertThat(new PageSpec(1, 10).pageable().getPageSize()).isEqualTo(10);
    }
}
