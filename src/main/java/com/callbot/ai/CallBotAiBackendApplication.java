package com.callbot.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CallBotAiBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(CallBotAiBackendApplication.class, args);
	}

}
