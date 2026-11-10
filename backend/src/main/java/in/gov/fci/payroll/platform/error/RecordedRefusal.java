package in.gov.fci.payroll.platform.error;

import java.util.List;

/**
 * A refusal whose recorded outcome is kept: the service saved the state that explains the refusal
 * (e.g. a case Blocked by the duplicate check) and then refuses. A method that may throw it is
 * {@code @Transactional(noRollbackFor = RecordedRefusal.class)}, never {@code noRollbackFor =
 * FciException.class}: any other refusal still rolls the whole change back (all or nothing).
 */
public class RecordedRefusal extends FciException {

    private static final long serialVersionUID = 1L;

    public RecordedRefusal(ErrorCode errorCode) {
        super(errorCode);
    }

    public RecordedRefusal(ErrorCode errorCode, Object[] messageArgs) {
        super(errorCode, messageArgs, List.of(), null);
    }
}
