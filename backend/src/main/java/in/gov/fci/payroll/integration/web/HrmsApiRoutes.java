package in.gov.fci.payroll.integration.web;

import in.gov.fci.payroll.integration.api.HrmsApi;
import in.gov.fci.payroll.integration.config.HrmsApiProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UrlPathHelper;

/**
 * Where each HRMS API is published (SRS-INT-1, SRS-INT-16): the path of every API whose URL setting
 * is set for the INBOUND direction, and nothing else. An API with no setting has no route, so a
 * call to it is not served at all. Read once at start-up. A path under a browser or actuator
 * prefix, or two APIs on the same method and path, stops the start-up: the settings are wrong.
 */
@Component
@Profile("online")
public class HrmsApiRoutes {

    private static final List<String> RESERVED =
            List.of("/v1", "/auth", "/actuator", "/menu", "/i18n", "/error");
    private static final UrlPathHelper PATHS = UrlPathHelper.defaultInstance;

    private final Map<HrmsApi, String> pathOf = new EnumMap<>(HrmsApi.class);
    private final Map<String, HrmsApi> byKey = new HashMap<>();
    private final Set<String> paths;

    public HrmsApiRoutes(HrmsApiProperties properties) {
        for (HrmsApi api : HrmsApi.values()) {
            properties
                    .routePath(api)
                    .ifPresent(
                            path -> {
                                String clean = path.replaceAll("/+$", "");
                                if (clean.isEmpty() || reserved(clean)) {
                                    throw new IllegalStateException(
                                            "HRMS API "
                                                    + api.code()
                                                    + " path is not allowed: "
                                                    + clean);
                                }
                                HrmsApi other = byKey.put(api.method() + " " + clean, api);
                                if (other != null) {
                                    throw new IllegalStateException(
                                            "HRMS APIs "
                                                    + other.code()
                                                    + " and "
                                                    + api.code()
                                                    + " share a method and path");
                                }
                                pathOf.put(api, clean);
                            });
        }
        this.paths = pathOf.values().stream().collect(Collectors.toUnmodifiableSet());
    }

    private static boolean reserved(String path) {
        return RESERVED.stream().anyMatch(r -> path.equals(r) || path.startsWith(r + "/"));
    }

    /** The published APIs and their paths. */
    public Map<HrmsApi, String> published() {
        return Map.copyOf(pathOf);
    }

    /** Whether the request path belongs to a published HRMS API (any method). */
    public boolean isHrmsPath(HttpServletRequest request) {
        return paths.contains(normalise(PATHS.getPathWithinApplication(request)));
    }

    /** The API a request is for, by method and path. */
    public Optional<HrmsApi> match(HttpServletRequest request) {
        return Optional.ofNullable(
                byKey.get(
                        request.getMethod()
                                + " "
                                + normalise(PATHS.getPathWithinApplication(request))));
    }

    /** Whether the request is the token API. */
    public boolean isToken(HttpServletRequest request) {
        return match(request).filter(api -> api == HrmsApi.TOKEN).isPresent();
    }

    private static String normalise(String path) {
        return path.replaceAll("/+$", "");
    }
}
