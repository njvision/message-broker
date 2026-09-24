package com.integration.simulator;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "simulator")
public record SimulatorProperties(
        @DefaultValue("http://localhost:8080") String brokerUrl,
        @DefaultValue Receiver receiver,
        @DefaultValue Sender sender) {

    public record Receiver(
            @DefaultValue("false") boolean enabled,
            String name,
            @DefaultValue("default") String group,
            String callbackUrl,
            @DefaultValue("100") int historyLimit) {
    }

    public record Sender(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("sender") String name,
            @DefaultValue("all") String defaultTarget,
            @DefaultValue("false") boolean auto,
            @DefaultValue("5s") Duration interval) {
    }
}
