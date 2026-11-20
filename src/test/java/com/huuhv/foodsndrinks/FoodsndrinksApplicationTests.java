package com.huuhv.foodsndrinks;

import org.springframework.context.annotation.Import;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class FoodsndrinksApplicationTests {

	@Test
	void contextLoads() {
	}

}
