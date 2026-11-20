package com.huuhv.foodsndrinks;

import com.huuhv.foodsndrinks.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mọi {@code @SpringBootTest} phải khai báo qua {@link IntegrationTest @IntegrationTest}: chạy trên MySQL của
 * Testcontainers và chỉ với profile {@code test}, không bao giờ trên database hay secret của dev. {@code @SpringBootTest}
 * trần vẫn bật profile {@code dev} (khi chạy ngoài Maven) và nối được vào DB dev, nên test này báo thẳng tên class.
 */
class IntegrationTestConventionTest {

    @Test
    void everySpringBootTest_usesIntegrationTestAnnotation() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                // Test class là package-private, có thể là nested class. Bỏ qua chính các annotation ghép.
                return !beanDefinition.getMetadata().isAnnotation();
            }
        };
        scanner.addIncludeFilter(new AnnotationTypeFilter(SpringBootTest.class));

        ClassLoader loader = getClass().getClassLoader();
        List<Class<?>> springBootTests = new ArrayList<>();
        for (var candidate : scanner.findCandidateComponents("com.huuhv.foodsndrinks")) {
            // initialize = false: không chạy static initializer của các test class khác
            springBootTests.add(Class.forName(candidate.getBeanClassName(), false, loader));
        }
        assertThat(springBootTests).as("@SpringBootTest classes").hasSizeGreaterThan(5);

        List<String> missing = springBootTests.stream()
                .filter(type -> !MergedAnnotations.from(type, SearchStrategy.TYPE_HIERARCHY).isPresent(IntegrationTest.class))
                .map(Class::getName)
                .toList();
        assertThat(missing).as("@SpringBootTest không qua @IntegrationTest").isEmpty();
    }
}
