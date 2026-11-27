package com.huuhv.foodsndrinks.config;

import com.huuhv.foodsndrinks.config.properties.ExcelProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Excel import/export (Apache POI): đăng ký {@link ExcelProperties} cho các service trong {@code service/excel}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ExcelProperties.class)
public class ExcelConfig {
}
