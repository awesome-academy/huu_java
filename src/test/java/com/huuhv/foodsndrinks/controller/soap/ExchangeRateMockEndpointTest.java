package com.huuhv.foodsndrinks.controller.soap;

import com.huuhv.foodsndrinks.config.WebServiceConfig;
import com.huuhv.foodsndrinks.config.properties.SoapProperties;
import com.huuhv.foodsndrinks.service.OrderService;
import com.huuhv.foodsndrinks.service.ProductService;
import com.huuhv.foodsndrinks.service.soap.MockExchangeRateService;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webservices.test.autoconfigure.server.WebServiceServerTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.ws.test.server.MockWebServiceClient;
import org.springframework.xml.transform.StringSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.ws.test.server.RequestCreators.withPayload;
import static org.springframework.ws.test.server.ResponseMatchers.clientOrSenderFault;
import static org.springframework.ws.test.server.ResponseMatchers.noFault;
import static org.springframework.ws.test.server.ResponseMatchers.xpath;

/**
 * Endpoint tỷ giá MOCK ({@code xsd/exchange-rate.xsd}): chỉ tồn tại khi cờ
 * {@code app.soap.exchange-rate.mock-endpoint.enabled} bật; request/response qua cùng interceptor validate XSD và resolver
 * fault với SOAP service thật.
 */
class ExchangeRateMockEndpointTest {

    private static final Map<String, String> NS = Map.of("r", ExchangeRateMockEndpoint.NAMESPACE);

    @Nested
    @WebServiceServerTest(endpoints = {ExchangeRateMockEndpoint.class, StoreEndpoint.class},
            properties = SoapProperties.ExchangeRate.MOCK_ENDPOINT_ENABLED + "=true")
    @Import({WebServiceConfig.class, MockExchangeRateService.class})
    class WhenEnabled {

        @Autowired private MockWebServiceClient client;
        @Autowired private ApplicationContext context;
        @MockitoBean private ProductService productService;
        @MockitoBean private OrderService orderService;

        @Test
        @WithMockUser(username = "svc", roles = "USER")
        void returnsAPositiveRateForTheRequestedPair_validAgainstTheContract() {
            client.sendRequest(withPayload(request("USD", "VND")))
                    .andExpect(noFault())
                    .andExpect(xpath("/r:GetExchangeRateResponse/r:baseCurrency", NS).evaluatesTo("USD"))
                    .andExpect(xpath("/r:GetExchangeRateResponse/r:quoteCurrency", NS).evaluatesTo("VND"))
                    .andExpect(xpath("/r:GetExchangeRateResponse/r:rate > 25000 and /r:GetExchangeRateResponse/r:rate < 26000",
                            NS).evaluatesTo(true))
                    // Mốc đầu giờ, UTC, không có phần lẻ thừa
                    .andExpect(xpath("substring(/r:GetExchangeRateResponse/r:asOf, 15)", NS).evaluatesTo("00:00Z"));
        }

        @Test
        @WithMockUser(username = "svc", roles = "USER")
        void unsupportedPair_isAFixedClientFault_andMalformedCodesAreRejectedByTheContract() {
            client.sendRequest(withPayload(request("USD", "XXX")))
                    .andExpect(clientOrSenderFault(WebServiceConfig.FAULT_INVALID_REQUEST));
            for (String[] invalid : List.of(new String[]{"usd", "VND"}, new String[]{"USDD", "VND"},
                    new String[]{"", "VND"})) {
                client.sendRequest(withPayload(request(invalid[0], invalid[1])))
                        .andExpect(clientOrSenderFault(WebServiceConfig.FAULT_VALIDATION));
            }
        }

        @Test
        void mockEndpoint_andItsWsdl_areRegistered() {
            assertThat(context.getBeansOfType(ExchangeRateMockEndpoint.class)).hasSize(1);
            assertThat(context.containsBean(WebServiceConfig.EXCHANGE_RATE_WSDL)).isTrue();
            assertThat(context.containsBean(WebServiceConfig.STORE_WSDL)).isTrue();
        }
    }

    @Nested
    @WebServiceServerTest(endpoints = {ExchangeRateMockEndpoint.class, StoreEndpoint.class},
            properties = SoapProperties.ExchangeRate.MOCK_ENDPOINT_ENABLED + "=false")
    @Import({WebServiceConfig.class, MockExchangeRateService.class})
    class WhenDisabled {

        @Autowired private ApplicationContext context;
        @MockitoBean private ProductService productService;
        @MockitoBean private OrderService orderService;

        @Test
        void neitherTheEndpoint_norItsService_norItsWsdl_exist_butTheRealServiceDoes() {
            // Prod: không phục vụ tỷ giá giả và không công bố hợp đồng của nó
            assertThat(context.getBeansOfType(ExchangeRateMockEndpoint.class)).isEmpty();
            assertThat(context.getBeansOfType(MockExchangeRateService.class)).isEmpty();
            assertThat(context.containsBean(WebServiceConfig.EXCHANGE_RATE_WSDL)).isFalse();
            assertThat(context.getBeansOfType(StoreEndpoint.class)).hasSize(1);
            assertThat(context.containsBean(WebServiceConfig.STORE_WSDL)).isTrue();
        }
    }

    private static StringSource request(String base, String quote) {
        return new StringSource("<GetExchangeRateRequest xmlns=\"" + ExchangeRateMockEndpoint.NAMESPACE + "\">"
                + "<baseCurrency>" + base + "</baseCurrency><quoteCurrency>" + quote + "</quoteCurrency>"
                + "</GetExchangeRateRequest>");
    }
}
