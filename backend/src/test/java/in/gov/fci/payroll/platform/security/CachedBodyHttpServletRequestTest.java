package in.gov.fci.payroll.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ServletInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * A request body read once by a security filter (JSON key guard, idempotency) and read again by the
 * controller: the same bytes, in the declared encoding, any number of times.
 */
class CachedBodyHttpServletRequestTest {

    private static final byte[] BODY = "{\"name\":\"Ramé\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    void theBodyIsReadAgainAsOftenAsAsked() throws IOException {
        CachedBodyHttpServletRequest request =
                new CachedBodyHttpServletRequest(new MockHttpServletRequest(), BODY);
        assertThat(request.getContentLength()).isEqualTo(BODY.length);
        assertThat(request.getContentLengthLong()).isEqualTo(BODY.length);
        ServletInputStream first = request.getInputStream();
        assertThat(first.isReady()).isTrue();
        assertThat(first.isFinished()).isFalse();
        assertThat(first.read()).isEqualTo('{');
        byte[] rest = new byte[BODY.length];
        int n = first.read(rest, 0, rest.length);
        assertThat(n).isEqualTo(BODY.length - 1);
        assertThat(first.isFinished()).isTrue();
        assertThat(first.read()).isEqualTo(-1);
        assertThat(request.getInputStream().readAllBytes()).isEqualTo(BODY);
    }

    @Test
    void theReaderUsesTheDeclaredEncodingOrUtf8() throws IOException {
        MockHttpServletRequest plain = new MockHttpServletRequest();
        assertThat(new CachedBodyHttpServletRequest(plain, BODY).getReader().readLine())
                .isEqualTo("{\"name\":\"Ramé\"}");
        MockHttpServletRequest latin = new MockHttpServletRequest();
        latin.setCharacterEncoding("ISO-8859-1");
        assertThat(new CachedBodyHttpServletRequest(latin, BODY).getReader().readLine())
                .isEqualTo(new String(BODY, StandardCharsets.ISO_8859_1));
    }

    @Test
    void anAsynchronousReadIsRefused() {
        ServletInputStream in =
                new CachedBodyHttpServletRequest(new MockHttpServletRequest(), BODY)
                        .getInputStream();
        assertThatThrownBy(() -> in.setReadListener(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
