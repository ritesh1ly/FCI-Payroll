package in.gov.fci.payroll.increment.orchestration;

import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The order copies of a pay scale, the drawn order card of M-03 (Wireframes v6.1 orderCard, FRS
 * §6.4.3, A-41; SRS-G-30; WF-1303, API-17-1 in PENDING). Keyed by the scale code on {@code
 * document_attachment}. A version's mandatory link to its Active revision circular (M-25, SRS
 * l.3950) is unchanged: a copy stored here is the card's own record and satisfies no version. Reads
 * are M-03 VIEW; a copy is stored by the holder of M-03 CREATE at Headquarters, as a version is
 * proposed (D17-10). Type, size and count are the document module's.
 */
@Service
@Loggable
public class PayScaleOrders {

    static final String ENTITY = "pay_scale_band";

    private final NamedParameterJdbcTemplate named;
    private final Authorization policy;
    private final DocumentStore documents;

    public PayScaleOrders(
            NamedParameterJdbcTemplate named, Authorization policy, DocumentStore documents) {
        this.named = named;
        this.policy = policy;
        this.documents = documents;
    }

    /** SRS-G-30: the copies stored with a scale; an unknown scale is a 404. */
    @Transactional(readOnly = true)
    public List<DocumentStore.AttachmentView> orders(AccessContext context, String code) {
        policy.require(context, PayScaleService.SCREEN, "VIEW");
        requireScale(code);
        return documents.active(ENTITY, code);
    }

    /**
     * SRS-G-30: stores a copy with a scale.
     *
     * @throws FciException access denied away from Headquarters, not found for an unknown scale
     */
    @Transactional
    public DocumentStore.AttachmentView attach(
            AccessContext context, String code, DocumentStore.NewAttachment order) {
        policy.require(context, PayScaleService.SCREEN, "CREATE");
        if (!policy.isAtHeadquarters(context)) {
            throw new FciException(CommonErrorCode.ACCESS_DENIED);
        }
        requireScale(code);
        return documents.attach(
                context.loginId(),
                new DocumentStore.NewAttachment(
                        ENTITY,
                        code,
                        order.documentTypeCode(),
                        order.referenceNumber(),
                        order.orderDate(),
                        order.issuingAuthority(),
                        order.fileName(),
                        order.content(),
                        false));
    }

    /** One stored copy, as a file of that scale (never another record's); every read is logged. */
    @Transactional
    public DocumentStore.Content file(
            AccessContext context, String sourceIp, String code, long attachmentId) {
        policy.require(context, PayScaleService.SCREEN, "VIEW");
        requireScale(code);
        return documents.read(context.loginId(), sourceIp, ENTITY, code, attachmentId);
    }

    private void requireScale(String code) {
        Long found =
                named.queryForObject(
                        "SELECT COUNT(*) FROM pay_scale_band WHERE band_code = :code",
                        new MapSqlParameterSource("code", code),
                        Long.class);
        if (RowCounts.of(found) == 0) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
    }
}
