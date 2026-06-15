package com.ruleup.ruleup_backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)   // 테스트용 MySQL 컨테이너 자동 기동
@SpringBootTest
class RuleupBackendApplicationTests {

	@Test
	void contextLoads() {
	}

}
