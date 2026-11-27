package com.huuhv.foodsndrinks.config;

import com.huuhv.foodsndrinks.logging.LogSanitizer;
import com.huuhv.foodsndrinks.service.messaging.MessageTypes;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import org.springframework.jms.support.converter.JacksonJsonMessageConverter;
import org.springframework.jms.support.converter.MessageConversionException;
import org.springframework.jms.support.converter.MessageType;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.Map;

/**
 * Message JMS dạng JSON ({@code TextMessage}, Jackson 3), kiểu payload ghi bằng alias trong property
 * {@value MessageTypes#TYPE_ID_PROPERTY} theo whitelist {@link MessageTypes#TYPE_ID_MAPPINGS}. Không dùng
 * {@code ObjectMessage} (Java deserialization).
 * <ul>
 *   <li>Đọc: alias không có trong whitelist (kể cả tên class đầy đủ) → {@link MessageConversionException}. Converter gốc
 *       của Spring rơi về {@code ClassUtils.forName(typeId)} khi alias không khớp — tức là bên gửi chọn được class để
 *       Jackson khởi tạo (OWASP A08); ở đây không bao giờ nạp class theo dữ liệu trong message.</li>
 *   <li>Ghi: class chưa có alias → lỗi ngay phía gửi, không ghi tên class đầy đủ vào message.</li>
 *   <li>Lỗi đọc ném ra không chứa dữ liệu bên gửi: thông điệp gốc của Jackson/Spring nối nguyên alias hoặc một đoạn
 *       JSON (kể cả CR/LF) và được container in ra log. Exception mới chỉ giữ tên loại lỗi + {@code JMSMessageID} đã làm
 *       sạch, bỏ cả cause. Lỗi JMS khi đọc message (property sai kiểu…) cũng ra cùng loại exception này — đọc
 *       message không bao giờ ném {@code JMSException}.</li>
 * </ul>
 */
public class AliasOnlyJsonMessageConverter extends JacksonJsonMessageConverter {

    /** Độ dài tối đa của {@code JMSMessageID} đưa vào thông điệp lỗi. */
    static final int MAX_MESSAGE_ID_LENGTH = 100;

    private final JsonMapper jsonMapper;
    private final Map<String, Class<?>> aliasToClass;
    private final Map<Class<?>, String> classToAlias;

    public AliasOnlyJsonMessageConverter(JsonMapper jsonMapper, Map<String, Class<?>> typeIdMappings) {
        super(jsonMapper);
        this.jsonMapper = jsonMapper;
        this.aliasToClass = Map.copyOf(typeIdMappings);
        Map<Class<?>, String> reverse = new HashMap<>();
        typeIdMappings.forEach((alias, type) -> {
            if (reverse.put(type, alias) != null) {
                throw new IllegalArgumentException("Class " + type.getName() + " has more than one message type alias");
            }
        });
        this.classToAlias = Map.copyOf(reverse);
        setTargetType(MessageType.TEXT);
        // Không gọi setTypeIdMappings/setTrustedPackages của lớp cha: hai method bị ghi đè bên dưới tự tra whitelist
        setTypeIdPropertyName(MessageTypes.TYPE_ID_PROPERTY);
    }

    @Override
    protected void setTypeIdOnMessage(Object object, Message message) throws JMSException {
        String alias = classToAlias.get(object.getClass());
        if (alias == null) {
            throw new MessageConversionException("No message type alias registered for " + object.getClass().getName()
                    + " (add it to MessageTypes.TYPE_ID_MAPPINGS)");
        }
        message.setStringProperty(MessageTypes.TYPE_ID_PROPERTY, alias);
    }

    @Override
    protected JavaType getJavaTypeForMessage(Message message) throws JMSException {
        String alias = message.getStringProperty(MessageTypes.TYPE_ID_PROPERTY);
        Class<?> type = alias == null ? null : aliasToClass.get(alias);
        if (type == null) {
            // Không đưa alias vào thông điệp: là dữ liệu bên gửi
            throw new SanitizedConversionException("Missing or unknown message type alias on JMS message "
                    + describe(message));
        }
        return jsonMapper.constructType(type);
    }

    @Override
    public Object fromMessage(Message message) throws JMSException {
        try {
            return super.fromMessage(message);
        } catch (SanitizedConversionException ex) {
            throw ex;
        } catch (JMSException | RuntimeException ex) {
            // MessageConversionException của Spring và JacksonException (unchecked ở Jackson 3) đều có thể chứa payload.
            // JMSException (vd. MessageFormatException khi property _type không phải chuỗi) cũng phải thành lỗi chuyển
            // đổi: để thoát nguyên dạng thì Spring bọc nó thành "Failed to extract payload from [<message>]" — in toàn bộ
            // property bên gửi đặt ra log — và DLQ listener coi là lỗi tạm thời, giao lại message mãi.
            throw new SanitizedConversionException("Cannot convert JMS message " + describe(message) + " ("
                    + ex.getClass().getSimpleName() + ")");
        }
    }

    /** {@code JMSMessageID} đã bỏ ký tự ngắt dòng, cắt ngắn — định danh để đối chiếu với broker, không lộ payload. */
    static String describe(Message message) {
        try {
            return LogSanitizer.truncate(LogSanitizer.stripLineBreaks(message.getJMSMessageID()), MAX_MESSAGE_ID_LENGTH);
        } catch (JMSException | RuntimeException ex) {
            return "<unknown id>";
        }
    }

    /** Lỗi chuyển đổi mà thông điệp đã được làm sạch (không chứa dữ liệu bên gửi, không có cause). */
    static final class SanitizedConversionException extends MessageConversionException {

        SanitizedConversionException(String message) {
            super(message);
        }
    }
}
