package in.gov.fci.payroll.platform.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Logback converter for pattern (non-JSON) appenders: masks sensitive data and encodes CR/LF in the
 * formatted message. Register with {@code <conversionRule conversionWord="maskedMsg"
 * class="in.gov.fci.payroll.platform.logging.MaskingMessageConverter"/>} and use {@code
 * %maskedMsg}.
 */
public class MaskingMessageConverter extends MessageConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return LogSanitizer.encodeControlCharacters(SensitiveDataMasker.mask(super.convert(event)));
    }
}
