package in.gov.fci.payroll.configuration.web;

import in.gov.fci.payroll.configuration.api.ReferenceData;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The M-16 reference lists for the dropdowns of every screen: all lists in force today, from memory
 * (no database read). Any signed-in session may read them (codes and labels only, nothing
 * personal). The ETag is the business date and the REFERENCE region version, so a browser
 * revalidates with {@code If-None-Match} and gets 304 until an approved change or the next day.
 */
@RestController
@Profile("online")
@RequestMapping("/v1/reference")
@PreAuthorize("isAuthenticated()")
public class ReferenceController {

    private final ReferenceData reference;

    public ReferenceController(ReferenceData reference) {
        this.reference = reference;
    }

    @GetMapping("/lists")
    public ResponseEntity<Map<String, Object>> lists(
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        ReferenceData.Snapshot snapshot = reference.snapshot();
        String etag = "\"ref-" + snapshot.asOf() + "-" + snapshot.version() + "\"";
        CacheControl cache = CacheControl.noCache().cachePrivate();
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(etag)
                    .cacheControl(cache)
                    .build();
        }
        return ResponseEntity.ok()
                .eTag(etag)
                .cacheControl(cache)
                .body(Map.of("asOf", snapshot.asOf().toString(), "lists", snapshot.lists()));
    }
}
