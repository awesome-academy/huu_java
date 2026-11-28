package com.huuhv.foodsndrinks.config;

import com.huuhv.foodsndrinks.config.properties.SoapProperties;
import com.huuhv.foodsndrinks.service.soap.ExchangeRateSoapClient;
import com.huuhv.foodsndrinks.soap.exchangerate.GetExchangeRateRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.soap.saaj.SaajSoapMessageFactory;
import org.springframework.ws.transport.http.HttpUrlConnectionMessageSender;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * SOAP CLIENT của dịch vụ tỷ giá: dựng {@link WebServiceTemplate} cho {@link ExchangeRateSoapClient} từ
 * {@code app.soap.exchange-rate.*} ({@link SoapProperties.ExchangeRate}).
 * <ul>
 *   <li><b>URL + tài khoản chỉ từ cấu hình</b> (đã kiểm tra scheme http/https lúc bind) — không tham số nào của lời gọi
 *       đổi được đích.</li>
 *   <li><b>Timeout</b> kết nối + đọc (mặc định 3s): dịch vụ treo không giữ thread quá thời gian này.</li>
 *   <li><b>Không theo redirect:</b> 3xx thành lỗi — redirect có thể đưa request (kèm header {@code Authorization}) tới
 *       host khác.</li>
 *   <li><b>Marshaller JAXB</b> validate request và response theo {@code xsd/exchange-rate.xsd}, tắt DTD và external entity
 *       (response là XML của bên ngoài — chống XXE). Cố ý KHÔNG phải bean: một bean {@code Marshaller} trong context sẽ
 *       được các phần khác của Spring-WS/OXM nhặt dùng.</li>
 * </ul>
 * Không có URL (tính năng tắt) thì template không có đích mặc định; {@code ExchangeRateService} không gọi client.
 */
@Configuration(proxyBeanMethods = false)
public class WebServiceClientConfig {

    @Bean
    public ExchangeRateSoapClient exchangeRateSoapClient(SoapProperties soapProperties,
                                                         SaajSoapMessageFactory messageFactory) {
        return exchangeRateSoapClient(soapProperties.exchangeRate(), messageFactory);
    }

    /** Dùng lại được ngoài context (test gọi endpoint mock của app trên cổng ngẫu nhiên). */
    public static ExchangeRateSoapClient exchangeRateSoapClient(SoapProperties.ExchangeRate properties,
                                                                SaajSoapMessageFactory messageFactory) {
        Jaxb2Marshaller marshaller = exchangeRateMarshaller();
        WebServiceTemplate template = new WebServiceTemplate(messageFactory);
        template.setMarshaller(marshaller);
        template.setUnmarshaller(marshaller);
        template.setMessageSender(new ConfiguredHttpSender(properties));
        if (properties.hasUrl()) {
            template.setDefaultUri(properties.url());
        }
        template.afterPropertiesSet();
        return new ExchangeRateSoapClient(template);
    }

    public static Jaxb2Marshaller exchangeRateMarshaller() {
        Jaxb2Marshaller marshaller = new Jaxb2Marshaller();
        marshaller.setPackagesToScan(GetExchangeRateRequest.class.getPackageName());
        marshaller.setSchema(new ClassPathResource("xsd/exchange-rate.xsd"));
        marshaller.setSupportDtd(false);
        marshaller.setProcessExternalEntities(false);
        try {
            marshaller.afterPropertiesSet();
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot initialise the exchange-rate JAXB marshaller", ex);
        }
        return marshaller;
    }

    /** {@code HttpURLConnection} với timeout, HTTP Basic từ cấu hình và không tự theo redirect. */
    static final class ConfiguredHttpSender extends HttpUrlConnectionMessageSender {

        private final String authorization;

        ConfiguredHttpSender(SoapProperties.ExchangeRate properties) {
            setConnectionTimeout(properties.connectTimeout());
            setReadTimeout(properties.readTimeout());
            this.authorization = properties.hasCredentials()
                    ? "Basic " + Base64.getEncoder().encodeToString(
                            (properties.username() + ":" + properties.password()).getBytes(StandardCharsets.UTF_8))
                    : null;
        }

        @Override
        protected void prepareConnection(HttpURLConnection connection) throws IOException {
            super.prepareConnection(connection);
            connection.setInstanceFollowRedirects(false);
            if (authorization != null) {
                connection.setRequestProperty(HttpHeaders.AUTHORIZATION, authorization);
            }
        }
    }
}
