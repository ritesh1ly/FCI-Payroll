package in.gov.fci.payroll.platform.i18n;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Label bundles for the frontend: {@code /api/i18n/{en|hi}} with ETag, and the resolved locale. */
@RestController
@org.springframework.context.annotation.Profile("online")
@PreAuthorize("permitAll()")
public class I18nController {

    private final MessageBundles bundles;

    public I18nController(MessageBundles bundles) {
        this.bundles = bundles;
    }

    @GetMapping("/i18n/{locale}")
    public ResponseEntity<byte[]> bundle(
            @PathVariable @Pattern(regexp = "^(en|hi)$") String locale,
            @RequestHeader(name = "If-None-Match", required = false) String ifNoneMatch) {
        MessageBundles.Bundle bundle = bundles.bundle(locale).orElseThrow();
        if (bundle.etag().equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(bundle.etag()).build();
        }
        return ResponseEntity.ok()
                .eTag(bundle.etag())
                .cacheControl(
                        CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic().mustRevalidate())
                .contentType(
                        new MediaType(
                                "application", "json", java.nio.charset.StandardCharsets.UTF_8))
                .body(bundle.json());
    }

    /** The locale for a visitor who is not signed in: cookie > Accept-Language > default. */
    @GetMapping("/i18n")
    public ResponseEntity<java.util.Map<String, String>> resolved(HttpServletRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(java.util.Map.of("locale", bundles.resolve(request, null)));
    }
}
