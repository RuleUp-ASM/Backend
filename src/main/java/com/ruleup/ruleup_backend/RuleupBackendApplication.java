package com.ruleup.ruleup_backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableCaching   // GET /categories 등 정적 데이터 캐시 활성화
public class RuleupBackendApplication {
	public static void main(String[] args) {
		SpringApplication.run(RuleupBackendApplication.class, args);
	}
}