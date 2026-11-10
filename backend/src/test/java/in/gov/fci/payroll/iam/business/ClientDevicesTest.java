package in.gov.fci.payroll.iam.business;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** SRS-G-50: the device and browser of a sign-in, read from its User-Agent, never guessed. */
class ClientDevicesTest {

    @ParameterizedTest(name = "{1}")
    @CsvSource(
            delimiter = '|',
            value = {
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
                        + " Chrome/140.0.0.0 Safari/537.36|Chrome 140 · Windows",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
                        + " Chrome/140.0.0.0 Safari/537.36 Edg/140.0.0.0|Edge 140 · Windows",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_6) AppleWebKit/605.1.15 (KHTML, like"
                        + " Gecko) Version/18.4 Safari/605.1.15|Safari 18 · Mac",
                "Mozilla/5.0 (iPhone; CPU iPhone OS 18_4 like Mac OS X) AppleWebKit/605.1.15"
                        + " (KHTML, like Gecko) Version/18.4 Mobile/15E148 Safari/604.1|Safari 18 ·"
                        + " iPhone",
                "Mozilla/5.0 (iPad; CPU OS 18_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like"
                        + " Gecko) CriOS/140.0 Mobile/15E148 Safari/604.1|Chrome 140 · iPad",
                "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko)"
                        + " Chrome/140.0.0.0 Mobile Safari/537.36|Chrome 140 · Android phone",
                "Mozilla/5.0 (Linux; Android 15; SM-X910) AppleWebKit/537.36 (KHTML, like Gecko)"
                        + " Chrome/140.0.0.0 Safari/537.36|Chrome 140 · Android tablet",
                "Mozilla/5.0 (X11; Linux x86_64; rv:140.0) Gecko/20100101 Firefox/140.0|Firefox 140"
                        + " · Linux",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
                        + " Chrome/139.0.0.0 Safari/537.36 OPR/124.0.0.0|Opera 124 · Windows",
                "curl/8.9.1|Other · Other",
            })
    void SRS_G_50_readsTheBrowserAndDeviceOfASignIn(String userAgent, String expected) {
        assertThat(ClientDevices.describe(userAgent)).isEqualTo(expected);
    }

    @Test
    void SRS_G_50_noUserAgentIsShownAsADash() {
        assertThat(ClientDevices.describe(null)).isEqualTo("—");
        assertThat(ClientDevices.describe("  ")).isEqualTo("—");
    }
}
