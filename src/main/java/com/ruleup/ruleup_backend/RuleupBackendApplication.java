package com.ruleup.ruleup_backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RuleupBackendApplication {
	public static void main(String[] args) {
		SpringApplication.run(RuleupBackendApplication.class, args);
	}
}