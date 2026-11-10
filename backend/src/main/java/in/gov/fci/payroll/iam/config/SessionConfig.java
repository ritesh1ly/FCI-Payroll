package in.gov.fci.payroll.iam.config;

import in.gov.fci.payroll.platform.config.ApiProperties;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.core.convert.converter.GenericConverter;
import org.springframework.core.convert.support.GenericConversionService;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * Spring Session JDBC for the browser session (profile {@code online} only; the batch profile
 * excludes session auto-configuration):
 *
 * <ul>
 *   <li>attributes are stored as UTF-8 strings only; any other type is refused, so the session
 *       table never contains Java-serialised objects (no deserialisation gadgets);
 *   <li>the cookie is {@code __Host-FCISESSION; HttpOnly; Secure; SameSite=Strict; Path=/} (OWASP
 *       ASVS V3.4.1 to V3.4.5): the {@code __Host-} prefix makes the browser refuse a cookie of
 *       that name set by a subdomain or without Secure (no session fixation through a sibling
 *       host); Strict never sends it on a cross-site request. A local HTTP stack (secure-cookie
 *       false) keeps the plain name, as the prefix requires Secure.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@Profile("online")
public class SessionConfig {

    /** Picked up by Spring Session JDBC by bean name. */
    @Bean("springSessionConversionService")
    ConversionService springSessionConversionService() {
        GenericConversionService service = new GenericConversionService();
        service.addConverter(new StringOnlyConverter());
        return service;
    }

    @Bean
    CookieSerializer cookieSerializer(ApiProperties properties) {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName(cookieName(properties.session()));
        // "/" so the Next.js server-side render receives the cookie as well as /api.
        serializer.setCookiePath("/");
        serializer.setUseHttpOnlyCookie(true);
        serializer.setUseSecureCookie(properties.session().secureCookie());
        // Strict: the session cookie never travels with a request another site starts; a typed URL
        // or a bookmark still carries it. Every unsafe request also needs the CSRF header.
        serializer.setSameSite("Strict");
        return serializer;
    }

    /** {@code __Host-FCISESSION} on HTTPS, the plain name on a local HTTP stack. */
    public static String cookieName(ApiProperties.Session session) {
        return session.secureCookie() ? "__Host-" + session.cookieName() : session.cookieName();
    }

    /** String to bytes and back; everything else is a programming error and fails loudly. */
    static final class StringOnlyConverter implements GenericConverter {

        @Override
        public Set<ConvertiblePair> getConvertibleTypes() {
            return Set.of(
                    new ConvertiblePair(Object.class, byte[].class),
                    new ConvertiblePair(byte[].class, Object.class));
        }

        @Override
        public Object convert(Object source, TypeDescriptor sourceType, TypeDescriptor targetType) {
            if (source == null) {
                return null;
            }
            if (targetType.getType() == byte[].class) {
                if (!(source instanceof String value)) {
                    throw new IllegalArgumentException(
                            "Only String session attributes are allowed, got "
                                    + source.getClass().getName());
                }
                return value.getBytes(StandardCharsets.UTF_8);
            }
            return new String((byte[]) source, StandardCharsets.UTF_8);
        }
    }
}
