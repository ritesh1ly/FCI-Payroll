package in.gov.fci.payroll.document.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** SRS-G-30 (storage settings); HLD report output storage; DDL S25 storage_object_key. */
class StorageLayoutTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final String DEFAULT = "{prefix}/{entity}/{yyyy}/{mm}/{uuid}";

    @Test
    void SRS_G_30_theKeyFollowsTheConfiguredFolderInIndiaTime() {
        // 2026-03-31 20:00 UTC is already 1 April in India: the April folder.
        String key =
                StorageLayout.of("fci-payroll/uat", DEFAULT)
                        .keyOf("Form16_Certificate", Instant.parse("2026-03-31T20:00:00Z"), ID);

        assertThat(key).isEqualTo("fci-payroll/uat/form16_certificate/2026/04/" + ID);
    }

    @Test
    void SRS_G_30_aLayoutMayDropTheDateFolders() {
        assertThat(StorageLayout.of("out", "{prefix}/{uuid}").keyOf("x.y", Instant.EPOCH, ID))
                .isEqualTo("out/" + ID);
        assertThat(StorageLayout.of("out", "{entity}-{uuid}").keyOf("a b", Instant.EPOCH, ID))
                .isEqualTo("a_b-" + ID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/abs", "a//b", "a/../b", "Upper", "a/", "sp ace"})
    void SRS_G_30_aPrefixOutsideLowerCaseAsciiSegmentsIsRefused(String prefix) {
        assertThatThrownBy(() -> StorageLayout.of(prefix, DEFAULT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("storage-key-prefix");
    }

    @Test
    void SRS_G_30_aMissingOrOverlongPrefixIsRefused() {
        assertThatThrownBy(() -> StorageLayout.of(null, DEFAULT))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> StorageLayout.of("a".repeat(101), DEFAULT))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{prefix}/{entity}",
                "{prefix}/{bucket}/{uuid}",
                "/{uuid}",
                "{prefix}//{uuid}",
                "{prefix}/../{uuid}"
            })
    void SRS_G_30_aLayoutWithoutUuidOrWithUnknownTokensOrPathTricksIsRefused(String layout) {
        assertThatThrownBy(() -> StorageLayout.of("out", layout))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("storage-key-layout");
    }

    @Test
    void SRS_G_30_aNullLayoutIsRefused() {
        assertThatThrownBy(() -> StorageLayout.of("out", null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void SRS_G_30_aKeyLongerThanTheColumnIsRefused() {
        StorageLayout layout = StorageLayout.of("out", "{entity}/{uuid}");

        assertThatThrownBy(() -> layout.keyOf("e".repeat(500), Instant.EPOCH, ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("500");
    }

    @Test
    void SRS_G_30_aPrefixAndAKeyAtTheirLimitsAreAccepted() {
        StorageLayout layout = StorageLayout.of("a".repeat(100), "{prefix}/{entity}/{uuid}");
        String key = layout.keyOf("e".repeat(362), Instant.EPOCH, ID);
        assertThat(key).hasSize(StorageLayout.MAX_KEY);
        assertThatThrownBy(() -> layout.keyOf("e".repeat(363), Instant.EPOCH, ID))
                .isInstanceOf(IllegalStateException.class);
    }
}
