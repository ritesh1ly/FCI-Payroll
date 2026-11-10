package in.gov.fci.payroll.allowance.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

/**
 * An uploaded file that cannot be read is an I/O failure, never a silent empty document (R-AUD: the
 * stored evidence must be the bytes the maker sent). Every upload handler of these controllers
 * reads its file through the private {@code bytes(MultipartFile)} helper, exercised here with a
 * file whose {@code getBytes()} fails, one that is empty and one that is a normal file.
 */
class AllowanceUploadFailureTest {

    private static Stream<Class<?>> controllers() {
        return Stream.of(ClaimController.class);
    }

    private static byte[] read(Class<?> controller, MultipartFile file) throws Exception {
        Method bytes = controller.getDeclaredMethod("bytes", MultipartFile.class);
        bytes.setAccessible(true);
        try {
            return (byte[]) bytes.invoke(null, file);
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw ex;
        }
    }

    @Test
    void anUnreadableFileIsAnUncheckedIoFailureWithTheCauseKept() throws IOException {
        IOException cause = new IOException("disk gone");
        MultipartFile broken = mock(MultipartFile.class);
        when(broken.getBytes()).thenThrow(cause);

        controllers()
                .forEach(
                        c ->
                                assertThatThrownBy(() -> read(c, broken))
                                        .as(c.getSimpleName())
                                        .isInstanceOf(UncheckedIOException.class)
                                        .hasCause(cause));
    }

    @Test
    void aReadableFileIsHandedOverByteForByteAndAnEmptyOneStaysEmpty() throws Exception {
        MultipartFile pdf =
                new MockMultipartFile("file", "order.pdf", "application/pdf", new byte[] {1, 2, 3});
        MultipartFile empty =
                new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0]);

        for (Class<?> c : (Iterable<Class<?>>) controllers()::iterator) {
            assertThat(read(c, pdf)).as(c.getSimpleName()).containsExactly(1, 2, 3);
            assertThat(read(c, empty)).as(c.getSimpleName()).isEmpty();
        }
    }
}
