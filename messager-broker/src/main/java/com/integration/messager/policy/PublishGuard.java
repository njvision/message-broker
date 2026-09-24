package com.integration.messager.policy;

import com.integration.messager.config.MessagerProperties;
import com.integration.messager.model.ChatMessage;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PublishGuard {

    private final RabbitTemplate rabbitTemplate;
    private final MessageConverter messageConverter;
    private final MessagerProperties properties;
    private final DeadLetterChannel deadLetterChannel;

    @PostConstruct
    void register() {
        rabbitTemplate.setReturnsCallback(returned -> {
            String routingKey = returned.getRoutingKey();
            log.error("Message with routing key '{}' matched no queue ({} {}); dead-lettering it",
                    routingKey, returned.getReplyCode(), returned.getReplyText());

            Object payload = messageConverter.fromMessage(returned.getMessage());
            if (payload instanceof ChatMessage message) {
                deadLetterChannel.send(message, groupOf(routingKey),
                        "unroutable: " + returned.getReplyText());
            }
        });

        rabbitTemplate.setConfirmCallback((correlation, ack, cause) -> {
            if (!ack) {
                log.error("Broker refused a publish: {}", cause);
            }
        });
    }

    private String groupOf(String routingKey) {
        String prefix = properties.routingKeyFor("");
        return routingKey != null && routingKey.startsWith(prefix)
                ? routingKey.substring(prefix.length())
                : properties.broadcastKey();
    }
}
