package in.gov.fci.payroll.platform.web;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;

/** Response helpers shared by the controllers of every module. */
public final class Responses {

    private Responses() {}

    /** 200 with {@code Cache-Control: no-store}: personal or scoped data is never cached. */
    public static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    /**
     * Master data the same for every caller allowed to see it (no personal or scoped data): {@code
     * Cache-Control: private, no-cache} with {@code etag}, so the browser keeps it and asks again
     * on every use, answered 304 without a body while the tag holds (HLD §3.12). Only after the
     * caller's authorisation passed; the tag must change whenever the data does (a cache region's
     * version read BEFORE the data, so a race costs a 200, never a stale 304).
     */
    public static <T> ResponseEntity<T> revalidated(
            String etag, String ifNoneMatch, java.util.function.Supplier<T> body) {
        CacheControl cache = CacheControl.noCache().cachePrivate();
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.NOT_MODIFIED)
                    .eTag(etag)
                    .cacheControl(cache)
                    .build();
        }
        return ResponseEntity.ok().eTag(etag).cacheControl(cache).body(body.get());
    }
}
