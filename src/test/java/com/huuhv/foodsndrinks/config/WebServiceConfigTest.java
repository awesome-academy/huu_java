package com.huuhv.foodsndrinks.config;

import com.huuhv.foodsndrinks.config.properties.SoapProperties;
import com.huuhv.foodsndrinks.controller.soap.ExchangeRateMockEndpoint;
import com.huuhv.foodsndrinks.controller.soap.StoreEndpoint;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.Ordered;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.unit.DataSize;
import org.w3c.dom.Document;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.time.Duration;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Các hằng số của SOAP phải khớp với file cấu hình/hợp đồng mà chúng mô tả: filter chain {@code /ws/**} của
 * {@code SecurityConfig} bảo vệ đúng đường dẫn servlet, endpoint map đúng namespace của XSD — lệch là service bị mở
 * (rơi vào chain khác) hoặc không nhận request nào.
 */
class WebServiceConfigTest {

    @Test
    void servletPath_inApplicationYml_isThePathTheSoapSecurityChainProtects() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = yaml.getObject();

        assertThat(properties).isNotNull();
        assertThat(properties.getProperty("spring.webservices.path")).isEqualTo(SoapProperties.PATH).isEqualTo("/ws");
        // WSDL trả địa chỉ service theo host của request
        assertThat(String.valueOf(properties.get("spring.webservices.servlet.init.transformWsdlLocations")))
                .isEqualTo("true");
    }

    @Test
    void endpointNamespaces_areTheTargetNamespacesOfTheContracts() throws Exception {
        assertThat(targetNamespaceOf("xsd/foodsndrinks.xsd")).isEqualTo(StoreEndpoint.NAMESPACE);
        assertThat(targetNamespaceOf("xsd/exchange-rate.xsd")).isEqualTo(ExchangeRateMockEndpoint.NAMESPACE);
    }

    private static String targetNamespaceOf(String xsdPath) throws Exception {
        // Parser chỉ đọc file của chính app trên classpath; vẫn tắt DTD/external entity theo quy tắc chung
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setNamespaceAware(true);
        try (InputStream xsd = new ClassPathResource(xsdPath).getInputStream()) {
            Document schema = factory.newDocumentBuilder().parse(xsd);
            return schema.getDocumentElement().getAttribute("targetNamespace");
        }
    }

    @Test
    void faultResolver_runsBeforeSpringWsDefaultResolver_thatWouldLeakExceptionMessages() {
        assertThat(new WebServiceConfig.FaultResolver().getOrder()).isLessThan(Ordered.LOWEST_PRECEDENCE);
    }

    @Test
    void maxRequestSize_mustStayBetween1KbAnd1Mb() {
        SoapProperties.ExchangeRate exchangeRate = new SoapProperties.ExchangeRate("", "", "", Duration.ofSeconds(3),
                Duration.ofSeconds(3), Duration.ofMinutes(10), Duration.ofMinutes(1), new SoapProperties.MockEndpoint(false));

        assertThat(new SoapProperties(DataSize.ofKilobytes(64), exchangeRate).maxRequestSize().toBytes())
                .isEqualTo(64 * 1024);
        assertThatIllegalArgumentException().isThrownBy(() -> new SoapProperties(DataSize.ofBytes(512), exchangeRate));
        assertThatIllegalArgumentException().isThrownBy(() -> new SoapProperties(DataSize.ofMegabytes(2), exchangeRate));
    }
}
