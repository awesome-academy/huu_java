package com.huuhv.foodsndrinks.service.soap;

import com.huuhv.foodsndrinks.exception.ExchangeRateUnavailableException;
import com.huuhv.foodsndrinks.soap.exchangerate.GetExchangeRateRequest;
import com.huuhv.foodsndrinks.soap.exchangerate.GetExchangeRateResponse;
import org.springframework.oxm.XmlMappingException;
import org.springframework.ws.client.WebServiceFaultException;
import org.springframework.ws.client.WebServiceIOException;
import org.springframework.ws.client.WebServiceTransportException;
import org.springframework.ws.client.core.WebServiceTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;

/**
 * SOAP CLIENT của dịch vụ tỷ giá ({@code xsd/exchange-rate.xsd}): gọi {@code GetExchangeRate} bằng
 * {@link WebServiceTemplate} (dựng ở {@code config/WebServiceClientConfig}: URL + HTTP Basic từ cấu hình, connect/read
 * timeout, không theo redirect, marshaller JAXB validate theo XSD và tắt DTD/external entity).
 * <p>
 * Response là dữ liệu NGOÀI, không được tin: phải đúng XSD (marshaller), đúng cặp tiền đã hỏi, tỷ giá dương, mốc thời
 * gian có múi giờ và không nằm ở tương lai. Mọi lỗi — không kết nối được, quá thời gian, SOAP Fault, 401, response sai —
 * thành {@link ExchangeRateUnavailableException} chỉ mang MÃ LÝ DO: thông điệp của lỗi phía client có thể chứa fault string
 * hay XML của máy chủ bên kia nên không được giữ lại.
 * <p>
 * Không phải bean tự quét: tạo ở {@code WebServiceClientConfig} (cần template đã cấu hình).
 */
public class ExchangeRateSoapClient {

    /** Đồng hồ hai bên có thể lệch nhau vài giây/phút — mốc tỷ giá quá khoảng này ở tương lai là dữ liệu sai. */
    static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(5);

    private final WebServiceTemplate webServiceTemplate;
    private final InstantSource clock;

    public ExchangeRateSoapClient(WebServiceTemplate webServiceTemplate) {
        this(webServiceTemplate, InstantSource.system());
    }

    ExchangeRateSoapClient(WebServiceTemplate webServiceTemplate, InstantSource clock) {
        this.webServiceTemplate = webServiceTemplate;
        this.clock = clock;
    }

    /** Tỷ giá do dịch vụ trả về: 1 {@code base} = {@code rate} {@code quote}, có hiệu lực từ {@code asOf}. */
    public record Quote(BigDecimal rate, Instant asOf) {
    }

    /**
     * @param base  mã tiền gốc (ISO 4217, vd. {@code USD})
     * @param quote mã tiền định giá (vd. {@code VND})
     * @throws ExchangeRateUnavailableException mọi trường hợp không có được một tỷ giá hợp lệ
     */
    public Quote getRate(String base, String quote) {
        GetExchangeRateRequest request = new GetExchangeRateRequest();
        request.setBaseCurrency(base);
        request.setQuoteCurrency(quote);

        Object response;
        try {
            response = webServiceTemplate.marshalSendAndReceive(request);
        } catch (WebServiceTransportException ex) {
            // Mã HTTP lỗi không kèm SOAP Fault: 401/403 (sai tài khoản), 3xx (không theo redirect), 404, 5xx...
            // (là lớp con của WebServiceIOException nên phải bắt trước)
            throw new ExchangeRateUnavailableException("HTTP_ERROR");
        } catch (WebServiceIOException ex) {
            throw new ExchangeRateUnavailableException("TIMEOUT_OR_IO");
        } catch (WebServiceFaultException ex) {
            throw new ExchangeRateUnavailableException("SOAP_FAULT");
        } catch (XmlMappingException ex) {
            // Response không đúng XSD (hoặc không phải XML đọc được)
            throw new ExchangeRateUnavailableException("INVALID_RESPONSE");
        } catch (RuntimeException ex) {
            throw new ExchangeRateUnavailableException("CLIENT_ERROR:" + ex.getClass().getSimpleName());
        }
        return validated(response, base, quote);
    }

    private Quote validated(Object response, String base, String quote) {
        if (!(response instanceof GetExchangeRateResponse rate)) {
            throw new ExchangeRateUnavailableException("INVALID_RESPONSE");
        }
        if (!base.equals(rate.getBaseCurrency()) || !quote.equals(rate.getQuoteCurrency())) {
            throw new ExchangeRateUnavailableException("WRONG_CURRENCY_PAIR");
        }
        if (rate.getRate() == null || rate.getRate().signum() <= 0) {
            throw new ExchangeRateUnavailableException("INVALID_RATE");
        }
        // Mốc không có múi giờ thì không biết là thời điểm nào
        if (rate.getAsOf() == null || rate.getAsOf().getTimezone() == javax.xml.datatype.DatatypeConstants.FIELD_UNDEFINED) {
            throw new ExchangeRateUnavailableException("INVALID_AS_OF");
        }
        Instant asOf = rate.getAsOf().toGregorianCalendar().toInstant();
        if (asOf.isAfter(clock.instant().plus(MAX_FUTURE_SKEW))) {
            throw new ExchangeRateUnavailableException("AS_OF_IN_FUTURE");
        }
        return new Quote(rate.getRate(), asOf);
    }
}
