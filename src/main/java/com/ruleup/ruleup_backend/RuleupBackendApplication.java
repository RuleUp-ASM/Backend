package com.ruleup.ruleup_backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan   // ← AppProperties를 자동으로 읽게 해줌
public class RuleupBackendApplication {
	public static void main(String[] args) {
		SpringApplication.run(RuleupBackendApplication.class, args);
	}
}