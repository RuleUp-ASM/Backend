package com.ruleup.ruleup_backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableCaching   // GET /categories 등 정적 데이터 캐시 활성화
@EnableAsync     // 가입 이후 LLM 닉네임/사진 검수를 비동기로 처리 (응답 지연 방지)
public class RuleupBackendApplication {
	public static void main(String[] args) {
		SpringApplication.run(RuleupBackendApplication.class, args);
	}
}