package com.huuhv.foodsndrinks;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mọi {@code @SpringBootTest} phải chạy trên MySQL của Testcontainers ({@link TestcontainersConfiguration}), không bao giờ
 * trên database dev. Thiếu {@code @Import} thì context vẫn cố nối tới URL giả trong {@code config/application.yml} và lỗi
 * khó hiểu — test này báo thẳng tên class.
 */
class IntegrationTestConventionTest {

    @Test
    void everySpringBootTest_importsTestcontainersConfiguration() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                return true; // test class là package-private, có thể là nested class
            }
        };
        scanner.addIncludeFilter(new AnnotationTypeFilter(SpringBootTest.class));

        List<Class<?>> springBootTests = new ArrayList<>();
        for (var candidate : scanner.findCandidateComponents("com.huuhv.foodsndrinks")) {
            springBootTests.add(Class.forName(candidate.getBeanClassName()));
        }
        assertThat(springBootTests).as("@SpringBootTest classes").hasSizeGreaterThan(5);

        List<String> missing = springBootTests.stream()
                .filter(type -> MergedAnnotations.from(type, SearchStrategy.TYPE_HIERARCHY).stream(Import.class)
                        .flatMap(annotation -> Arrays.stream(annotation.getClassArray("value")))
                        .noneMatch(TestcontainersConfiguration.class::equals))
                .map(Class::getName)
                .toList();
        assertThat(missing).as("@SpringBootTest thiếu @Import(TestcontainersConfiguration.class)").isEmpty();
    }
}
