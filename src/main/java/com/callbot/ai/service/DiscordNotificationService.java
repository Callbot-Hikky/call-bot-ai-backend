package com.callbot.ai.service;

import com.callbot.ai.config.DiscordProperties;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class DiscordNotificationService {
    private static final Logger log = LoggerFactory.getLogger(DiscordNotificationService.class);

    private final DiscordProperties props;
    private final RestClient http = RestClient.create();

    public DiscordNotificationService(DiscordProperties props) {
        this.props = props;
    }

    public void sendReservationMessage(String content) {
        sendRaw(props.webhookRestaurant(), "Jarvis", content + "\n\n------\n");
    }

    public void sendClientSmsMessage(String content) {
        sendRaw(props.webhookClient(), "Alfred", content);
    }

    private void sendRaw(String url, String username, String content) {
        if (url == null || url.isBlank()) return;
        try {
            http.post().uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", username, "content", content))
                .retrieve().toBodilessEntity();
        } catch (Exception e) {
            log.warn("Discord notification failed: {}", e.getMessage());
        }
    }
}
