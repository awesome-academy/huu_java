package com.huuhv.foodsndrinks.controller.soap;

import com.huuhv.foodsndrinks.config.properties.SoapProperties;
import com.huuhv.foodsndrinks.service.soap.MockExchangeRateService;
import com.huuhv.foodsndrinks.soap.exchangerate.GetExchangeRateRequest;
import com.huuhv.foodsndrinks.soap.exchangerate.GetExchangeRateResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.ws.server.endpoint.annotation.Endpoint;
import org.springframework.ws.server.endpoint.annotation.PayloadRoot;
import org.springframework.ws.server.endpoint.annotation.RequestPayload;
import org.springframework.ws.server.endpoint.annotation.ResponsePayload;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import java.time.Instant;

/**
 * Endpoint MOCK của dịch vụ tỷ giá ({@code xsd/exchange-rate.xsd}) — đóng vai "dịch vụ bên ngoài" cho SOAP client của
 * app ở môi trường local/demo. Chỉ tồn tại khi {@code app.soap.exchange-rate.mock-endpoint.enabled=true} (dev, compose);
 * tắt cờ thì operation này không được map — request tới nó không có endpoint nào nhận.
 * <p>
 * Nằm sau cùng filter chain {@code /ws/**} với SOAP service thật nên CŨNG cần HTTP Basic: Spring-WS định tuyến theo
 * payload, không theo URL — mở một đường dẫn "public cho mock" là mở luôn mọi operation khác qua đường dẫn đó.
 */
@Endpoint
@RequiredArgsConstructor
@ConditionalOnBooleanProperty(SoapProperties.ExchangeRate.MOCK_ENDPOINT_ENABLED)
public class ExchangeRateMockEndpoint {

    /** Target namespace của {@code xsd/exchange-rate.xsd}. */
    public static final String NAMESPACE = "urn:foodsndrinks:exchange-rate";

    /** Tạo một lần: {@code DatatypeFactory.newInstance()} dò implementation qua ServiceLoader mỗi lần gọi. */
    private static final DatatypeFactory DATATYPES = newDatatypeFactory();

    private final MockExchangeRateService mockExchangeRateService;

    @PayloadRoot(namespace = NAMESPACE, localPart = "GetExchangeRateRequest")
    @ResponsePayload
    public GetExchangeRateResponse getExchangeRate(@RequestPayload GetExchangeRateRequest request) {
        MockExchangeRateService.Quote quote = mockExchangeRateService.quote(
                request.getBaseCurrency(), request.getQuoteCurrency(), Instant.now());

        GetExchangeRateResponse response = new GetExchangeRateResponse();
        response.setBaseCurrency(request.getBaseCurrency());
        response.setQuoteCurrency(request.getQuoteCurrency());
        response.setRate(quote.rate());
        // Instant#toString là ISO-8601 UTC ("...Z"), không có phần lẻ thừa với mốc đầu giờ
        response.setAsOf(DATATYPES.newXMLGregorianCalendar(quote.asOf().toString()));
        return response;
    }

    private static DatatypeFactory newDatatypeFactory() {
        try {
            return DatatypeFactory.newInstance();
        } catch (DatatypeConfigurationException ex) {
            throw new IllegalStateException("No DatatypeFactory implementation available", ex);
        }
    }
}
