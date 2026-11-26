package com.huuhv.foodsndrinks.config;

import com.huuhv.foodsndrinks.controller.admin.PendingOrderCountInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final String uploadDir;
    private final PendingOrderCountInterceptor pendingOrderCountInterceptor;

    public WebMvcConfig(@Value("${app.upload.dir:./uploads}") String uploadDir,
                        PendingOrderCountInterceptor pendingOrderCountInterceptor) {
        this.uploadDir = uploadDir;
        this.pendingOrderCountInterceptor = pendingOrderCountInterceptor;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String absolutePath = Paths.get(uploadDir).toAbsolutePath().normalize().toString();
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + absolutePath + "/");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(pendingOrderCountInterceptor).addPathPatterns("/admin", "/admin/**");
    }
}
