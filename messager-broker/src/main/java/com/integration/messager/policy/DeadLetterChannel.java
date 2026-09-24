package com.integration.messager.policy;

import com.integration.messager.config.MessagerProperties;
import com.integration.messager.model.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeadLetterChannel {

    private final RabbitTemplate rabbitTemplate;
    private final MessagerProperties properties;

    public void send(ChatMessage message, String group, String reason) {
        rabbitTemplate.convertAndSend(
                properties.exchangeDlx(),
                properties.routingKeyFor(group),
                message,
                amqp -> annotate(amqp, group, reason));

        log.warn("Message {} moved to the dead letter channel (group '{}'): {}",
                message.getId(), group, reason);
    }

    private Message annotate(Message amqp, String group, String reason) {
        amqp.getMessageProperties().setHeader("x-messager-dead-letter-reason", reason);
        amqp.getMessageProperties().setHeader("x-messager-group", group);
        amqp.getMessageProperties().setHeader("x-messager-dead-lettered-at", Instant.now().toString());
        return amqp;
    }
}
