package com.integration.simulator.receiver;

import com.integration.simulator.SimulatorProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;

@Slf4j
@Component
@ConditionalOnProperty(name = "simulator.receiver.enabled", havingValue = "true")
public class BrokerRegistrar {

    private final SimulatorProperties properties;
    private final RestClient restClient;

    private volatile int port;
    private volatile String receiverId;

    public BrokerRegistrar(SimulatorProperties properties, RestClient brokerRestClient) {
        this.properties = properties;
        this.restClient = brokerRestClient;
    }

    @EventListener
    void onWebServerReady(WebServerInitializedEvent event) {
        this.port = event.getWebServer().getPort();
    }

    @EventListener
    void register(ApplicationReadyEvent event) {
        String callbackUrl = callbackUrl();
        String group = properties.receiver().group();
        String name = name();

        try {
            Map<?, ?> response = restClient.post()
                    .uri(properties.brokerUrl() + "/api/receivers")
                    .body(Map.of("name", name, "group", group, "callbackUrl", callbackUrl))
                    .retrieve()
                    .body(Map.class);

            receiverId = response == null ? null : String.valueOf(response.get("id"));
            log.info("Registered with the agent as '{}' in group '{}' at {} - senders can address me as @{}",
                    name, group, callbackUrl, name);
        } catch (Exception ex) {
            log.error("Could not register with the agent at {}: {}",
                    properties.brokerUrl(), ex.getMessage());
        }
    }

    public String name() {
        String configured = properties.receiver().name();
        return configured != null && !configured.isBlank() ? configured : hostName();
    }

    @PreDestroy
    void unregister() {
        if (receiverId == null) {
            return;
        }
        try {
            restClient.delete()
                    .uri(properties.brokerUrl() + "/api/receivers/" + receiverId)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Unregistered receiver '{}'", receiverId);
        } catch (Exception ex) {
            log.warn("Could not unregister receiver '{}': {}", receiverId, ex.getMessage());
        }
    }

    private String callbackUrl() {
        String configured = properties.receiver().callbackUrl();
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return "http://" + hostName() + ":" + port + "/receive";
    }

    private String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException ex) {
            return "localhost";
        }
    }
}
