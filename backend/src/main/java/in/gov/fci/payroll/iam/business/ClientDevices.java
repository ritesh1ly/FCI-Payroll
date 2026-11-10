package in.gov.fci.payroll.iam.business;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SRS-G-50: the login record shows the device and browser of each sign-in. They are read from the
 * User-Agent the browser sent at sign-in (stored on {@code app_session.user_agent}); a value that
 * matches nothing known is shown as "Other", never guessed. Pure function: no I/O.
 */
public final class ClientDevices {

    /** A browser family and the pattern of its version, in matching order (Edge before Chrome). */
    private record Family(String name, Pattern version) {}

    private static final List<Family> BROWSERS =
            List.of(
                    new Family("Edge", Pattern.compile("Edg(?:e|A|iOS)?/(\\d+)")),
                    new Family("Opera", Pattern.compile("OPR/(\\d+)")),
                    new Family("Firefox", Pattern.compile("(?:Firefox|FxiOS)/(\\d+)")),
                    new Family("Chrome", Pattern.compile("(?:Chrome|CriOS)/(\\d+)")),
                    new Family("Safari", Pattern.compile("Version/(\\d+).*Safari/")));

    private ClientDevices() {}

    /** "Chrome 140 · Windows", "Safari 18 · iPhone", "Other · Other" or "—" without a value. */
    public static String describe(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "—";
        }
        return browser(userAgent) + " · " + device(userAgent);
    }

    static String browser(String userAgent) {
        for (Family family : BROWSERS) {
            Matcher m = family.version().matcher(userAgent);
            if (m.find()) {
                return family.name() + " " + m.group(1);
            }
        }
        return "Other";
    }

    static String device(String userAgent) {
        // Phones and tablets first: their User-Agents also name the desktop system they run on.
        if (userAgent.contains("iPhone")) {
            return "iPhone";
        }
        if (userAgent.contains("iPad")) {
            return "iPad";
        }
        if (userAgent.contains("Android")) {
            return userAgent.contains("Mobile") ? "Android phone" : "Android tablet";
        }
        if (userAgent.contains("Windows")) {
            return "Windows";
        }
        if (userAgent.contains("Mac OS X")) {
            return "Mac";
        }
        if (userAgent.contains("Linux")) {
            return "Linux";
        }
        return "Other";
    }
}
