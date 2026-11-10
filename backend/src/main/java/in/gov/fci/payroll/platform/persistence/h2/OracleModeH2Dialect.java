package in.gov.fci.payroll.platform.persistence.h2;

import java.sql.Types;
import org.hibernate.dialect.H2Dialect;
import org.hibernate.engine.jdbc.dialect.spi.DialectResolutionInfo;

/**
 * H2 in {@code MODE=Oracle} (ADR-0019 interim store) stores the target DDL's Oracle {@code DATE} as
 * {@code TIMESTAMP(0)}, exactly like Oracle's DATE carries a time. Schema validation must accept
 * that for {@code LocalDate} attributes; everything else is plain H2. Not used with Oracle.
 */
public class OracleModeH2Dialect extends H2Dialect {

    public OracleModeH2Dialect() {
        super();
    }

    public OracleModeH2Dialect(DialectResolutionInfo info) {
        super(info);
    }

    @Override
    public boolean equivalentTypes(int typeCode1, int typeCode2) {
        return isDateOrTimestamp(typeCode1) && isDateOrTimestamp(typeCode2)
                || super.equivalentTypes(typeCode1, typeCode2);
    }

    private static boolean isDateOrTimestamp(int typeCode) {
        return typeCode == Types.DATE || typeCode == Types.TIMESTAMP;
    }
}
