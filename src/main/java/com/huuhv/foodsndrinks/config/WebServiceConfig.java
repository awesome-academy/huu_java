package com.huuhv.foodsndrinks.config;

import com.huuhv.foodsndrinks.config.properties.SoapProperties;
import com.huuhv.foodsndrinks.controller.soap.ExchangeRateMockEndpoint;
import com.huuhv.foodsndrinks.controller.soap.SoapMessageReceiverAdapter;
import com.huuhv.foodsndrinks.controller.soap.StoreEndpoint;
import com.huuhv.foodsndrinks.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.ws.config.annotation.WsConfigurer;
import org.springframework.ws.server.EndpointInterceptor;
import org.springframework.ws.soap.SoapFault;
import org.springframework.ws.soap.saaj.SaajSoapMessageFactory;
import org.springframework.ws.soap.server.endpoint.SoapFaultDefinition;
import org.springframework.ws.soap.server.endpoint.SoapFaultMappingExceptionResolver;
import org.springframework.ws.soap.server.endpoint.interceptor.PayloadValidatingInterceptor;
import org.springframework.ws.transport.http.MessageDispatcherServlet;
import org.springframework.ws.wsdl.wsdl11.DefaultWsdl11Definition;
import org.springframework.xml.xsd.SimpleXsdSchema;
import org.springframework.xml.xsd.XsdSchema;

import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * SOAP server (Spring-WS, contract-first). {@code MessageDispatcherServlet} do Spring Boot đăng ký ở
 * {@code spring.webservices.path} = {@link SoapProperties#PATH}; bảo mật của đường dẫn đó: {@code SecurityConfig}
 * (HTTP Basic, stateless). Endpoint: {@code controller/soap}.
 * <ul>
 *   <li><b>Hợp đồng:</b> {@code xsd/foodsndrinks.xsd} → WSDL sinh tự động tại {@code /ws/foodsndrinks.wsdl} (tên bean
 *       = tên file WSDL; địa chỉ service trong WSDL được đổi theo host của request —
 *       {@code spring.webservices.servlet.init.transformWsdlLocations}).</li>
 *   <li><b>Validate theo XSD</b> cả request lẫn response ({@link PayloadValidatingInterceptor}): request sai hợp đồng bị
 *       từ chối bằng Fault {@code Client} trước khi tới endpoint — KHÔNG kèm chi tiết của parser
 *       ({@code addValidationErrorDetail=false}: chi tiết đó lặp lại giá trị client gửi).</li>
 *   <li><b>Lỗi → SOAP Fault với thông điệp cố định</b> ({@link FaultResolver}): resolver mặc định của Spring-WS
 *       ({@code SimpleSoapExceptionResolver}) đưa nguyên {@code ex.getMessage()} vào fault — lộ thông điệp của
 *       DB/thư viện. Resolver ở đây đứng trước nó và xử lý MỌI exception.</li>
 *   <li><b>XML không đọc được / có DOCTYPE</b> → 400 không body ({@link SoapMessageReceiverAdapter}). App không tự tạo XML
 *       parser nào: SAAJ ({@link SaajSoapMessageFactory}) từ chối DTD nên không có XXE.</li>
 * </ul>
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SoapProperties.class)
public class WebServiceConfig implements WsConfigurer {

    /** Tên bean của WSDL = tên file: {@code /ws/foodsndrinks.wsdl}. */
    public static final String STORE_WSDL = "foodsndrinks";

    /** Thông điệp CỐ ĐỊNH của SOAP Fault — phần duy nhất client thấy khi có lỗi (docs/soap). */
    public static final String FAULT_NOT_FOUND = "Resource not found";
    public static final String FAULT_INVALID_REQUEST = "Invalid request";
    public static final String FAULT_INTERNAL = "Internal error";
    public static final String FAULT_VALIDATION = "Validation error";

    /** Tên bean của WSDL dịch vụ tỷ giá MOCK: {@code /ws/exchangeRate.wsdl} (chỉ khi endpoint mock bật). */
    public static final String EXCHANGE_RATE_WSDL = "exchangeRate";

    private static final Resource STORE_XSD = new ClassPathResource("xsd/foodsndrinks.xsd");
    private static final Resource EXCHANGE_RATE_XSD = new ClassPathResource("xsd/exchange-rate.xsd");

    @Bean
    public XsdSchema storeSchema() {
        return new SimpleXsdSchema(STORE_XSD);
    }

    @Bean(name = STORE_WSDL)
    public DefaultWsdl11Definition storeWsdl(XsdSchema storeSchema) {
        DefaultWsdl11Definition wsdl = new DefaultWsdl11Definition();
        wsdl.setPortTypeName("FoodsndrinksPort");
        wsdl.setServiceName("FoodsndrinksService");
        wsdl.setTargetNamespace(StoreEndpoint.NAMESPACE);
        wsdl.setLocationUri(SoapProperties.PATH);
        wsdl.setSchema(storeSchema);
        return wsdl;
    }

    /** WSDL của endpoint tỷ giá mock — chỉ tồn tại cùng endpoint đó (dev/compose); prod không công bố hợp đồng này. */
    @Bean(name = EXCHANGE_RATE_WSDL)
    @ConditionalOnBooleanProperty(SoapProperties.ExchangeRate.MOCK_ENDPOINT_ENABLED)
    public DefaultWsdl11Definition exchangeRateWsdl() {
        DefaultWsdl11Definition wsdl = new DefaultWsdl11Definition();
        wsdl.setPortTypeName("ExchangeRatePort");
        wsdl.setServiceName("ExchangeRateService");
        wsdl.setTargetNamespace(ExchangeRateMockEndpoint.NAMESPACE);
        wsdl.setLocationUri(SoapProperties.PATH);
        wsdl.setSchema(loadedSchema(EXCHANGE_RATE_XSD));
        return wsdl;
    }

    /** Dùng chung cho servlet và {@link #messageReceiverHandlerAdapter}; tên bean là tên servlet tìm. */
    @Bean(name = MessageDispatcherServlet.DEFAULT_MESSAGE_FACTORY_BEAN_NAME)
    public SaajSoapMessageFactory messageFactory() {
        return new SaajSoapMessageFactory();
    }

    @Bean(name = MessageDispatcherServlet.DEFAULT_MESSAGE_RECEIVER_HANDLER_ADAPTER_BEAN_NAME)
    public SoapMessageReceiverAdapter messageReceiverHandlerAdapter(SaajSoapMessageFactory messageFactory) {
        SoapMessageReceiverAdapter adapter = new SoapMessageReceiverAdapter();
        adapter.setMessageFactory(messageFactory);
        return adapter;
    }

    @Bean
    public FaultResolver soapFaultResolver() {
        return new FaultResolver();
    }

    @Override
    public void addInterceptors(List<EndpointInterceptor> interceptors) {
        // Một interceptor cho mọi hợp đồng của /ws: payload phải khớp MỘT trong các XSD này (kể cả khi endpoint mock tắt —
        // khi đó request tỷ giá hợp lệ về XSD nhưng không có endpoint nào nhận)
        interceptors.add(validatingInterceptor(STORE_XSD, EXCHANGE_RATE_XSD));
    }

    /** {@link SimpleXsdSchema} không phải bean thì phải tự gọi {@code afterPropertiesSet()} (nạp file XSD). */
    private static SimpleXsdSchema loadedSchema(Resource xsd) {
        SimpleXsdSchema schema = new SimpleXsdSchema(xsd);
        try {
            schema.afterPropertiesSet();
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot load the SOAP XSD schema", ex);
        }
        return schema;
    }

    /** Thêm XSD (vd. của một service khác) = truyền thêm resource vào đây. */
    static PayloadValidatingInterceptor validatingInterceptor(Resource... schemas) {
        PayloadValidatingInterceptor interceptor = new PayloadValidatingInterceptor();
        interceptor.setSchemas(schemas);
        interceptor.setValidateRequest(true);
        interceptor.setValidateResponse(true);
        interceptor.setAddValidationErrorDetail(false);
        interceptor.setFaultStringOrReason(FAULT_VALIDATION);
        try {
            interceptor.afterPropertiesSet();
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot load the SOAP XSD schemas", ex);
        }
        return interceptor;
    }

    /**
     * Mọi exception của endpoint → SOAP Fault với thông điệp CỐ ĐỊNH, không bao giờ là {@code ex.getMessage()}:
     * không tìm thấy → Fault {@code Client} (đơn không tồn tại, đơn của người khác và giỏ hàng trả lời y hệt nhau — không
     * dò được đơn nào có thật); tham số sai → Fault {@code Client}; còn lại → Fault {@code Server} + log ERROR kèm stack
     * trace (chỉ trong log).
     */
    static class FaultResolver extends SoapFaultMappingExceptionResolver {

        FaultResolver() {
            // Trước SimpleSoapExceptionResolver (LOWEST_PRECEDENCE) — resolver đó đưa ex.getMessage() vào fault
            setOrder(Ordered.LOWEST_PRECEDENCE - 1);
            Properties mappings = new Properties();
            mappings.setProperty(ResourceNotFoundException.class.getName(), "CLIENT," + FAULT_NOT_FOUND);
            mappings.setProperty(IllegalArgumentException.class.getName(), "CLIENT," + FAULT_INVALID_REQUEST);
            setExceptionMappings(mappings);
            SoapFaultDefinition internal = new SoapFaultDefinition();
            internal.setFaultCode(SoapFaultDefinition.SERVER);
            internal.setFaultStringOrReason(FAULT_INTERNAL);
            internal.setLocale(Locale.ENGLISH);
            setDefaultFault(internal);
        }

        @Override
        protected void customizeFault(Object endpoint, Exception ex, SoapFault fault) {
            if (!(ex instanceof ResourceNotFoundException) && !(ex instanceof IllegalArgumentException)) {
                log.error("SOAP endpoint failed", ex);
            }
        }
    }
}
