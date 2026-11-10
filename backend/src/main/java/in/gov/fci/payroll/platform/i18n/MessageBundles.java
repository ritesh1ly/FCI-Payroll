package in.gov.fci.payroll.platform.i18n;

import in.gov.fci.payroll.platform.config.ApiProperties;
import in.gov.fci.payroll.platform.security.SecureTokens;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * The user-interface label bundles {@code i18n/messages_{en,hi}.properties} (UTF-8, identical keys;
 * Hindi is machine-assisted and marked needs-review). Labels only: data values (names, codes,
 * amounts) are never translated. Served as JSON with a strong ETag.
 */
@Component
public class MessageBundles {

    public static final Set<String> LOCALES = Set.of("en", "hi");

    /** One bundle as served: the JSON bytes and their ETag. */
    public record Bundle(String locale, byte[] json, String etag) {}

    private final Map<String, Bundle> bundles;
    private final Map<String, Map<String, String>> messages;
    private final ApiProperties.I18n settings;

    public MessageBundles(ObjectMapper json, ApiProperties properties) {
        this.settings = properties.i18n();
        Map<String, Map<String, String>> loaded = new TreeMap<>();
        Map<String, Bundle> served = new TreeMap<>();
        for (String locale : LOCALES) {
            Map<String, String> entries = load(locale);
            loaded.put(locale, entries);
            byte[] bytes;
            try {
                bytes = json.writeValueAsBytes(entries);
            } catch (tools.jackson.core.JacksonException ex) {
                throw new IllegalStateException("Cannot serialise bundle " + locale, ex);
            }
            served.put(
                    locale,
                    new Bundle(
                            locale,
                            bytes,
                            "\""
                                    + SecureTokens.sha256Hex(
                                                    new String(bytes, StandardCharsets.UTF_8))
                                            .substring(0, 32)
                                    + "\""));
        }
        if (!loaded.get("en").keySet().equals(loaded.get("hi").keySet())) {
            throw new IllegalStateException("messages_en and messages_hi must have identical keys");
        }
        this.messages = Collections.unmodifiableMap(loaded);
        this.bundles = Collections.unmodifiableMap(served);
    }

    public Optional<Bundle> bundle(String locale) {
        return Optional.ofNullable(bundles.get(locale));
    }

    public Map<String, String> messages(String locale) {
        return messages.getOrDefault(locale, messages.get("en"));
    }

    /**
     * Preference (if signed in and stored) > {@code FCI_LOCALE} cookie > Accept-Language > default.
     */
    public String resolve(HttpServletRequest request, String preference) {
        if (preference != null && LOCALES.contains(preference)) {
            return preference;
        }
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (settings.cookieName().equals(cookie.getName())
                        && LOCALES.contains(cookie.getValue())) {
                    return cookie.getValue();
                }
            }
        }
        String header = request.getHeader("Accept-Language");
        if (header != null) {
            try {
                for (Locale.LanguageRange range : Locale.LanguageRange.parse(header)) {
                    String language = range.getRange().split("-")[0].toLowerCase(Locale.ROOT);
                    if (LOCALES.contains(language)) {
                        return language;
                    }
                }
            } catch (IllegalArgumentException malformed) {
                // fall through to the default
            }
        }
        return settings.defaultLocale();
    }

    private static Map<String, String> load(String locale) {
        Properties properties = new Properties();
        try (InputStream in =
                        new ClassPathResource("i18n/messages_" + locale + ".properties")
                                .getInputStream();
                InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException ex) {
            throw new UncheckedIOException("Missing bundle " + locale, ex);
        }
        Map<String, String> entries = new TreeMap<>();
        for (String key : properties.stringPropertyNames()) {
            entries.put(key, properties.getProperty(key));
        }
        return entries;
    }

    /** For tests: every key of the English bundle. */
    public List<String> keys() {
        return List.copyOf(messages.get("en").keySet());
    }
}
