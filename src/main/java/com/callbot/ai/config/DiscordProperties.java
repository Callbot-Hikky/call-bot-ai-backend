package com.callbot.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.discord")
public record DiscordProperties(String webhookClient, String webhookRestaurant) {}
