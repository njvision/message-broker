package com.integration.messager.policy;

import com.integration.messager.config.MessagerProperties;
import com.integration.messager.model.ChatMessage;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class InvalidMessageChannel {

    private final RabbitTemplate rabbitTemplate;
    private final MessagerProperties properties;
    private final Validator validator;

    public List<String> validate(ChatMessage message) {
        if (message == null) {
            return List.of("message is null");
        }
        Set<ConstraintViolation<ChatMessage>> violations = validator.validate(message);
        return violations.stream()
                .map(v -> v.getPropertyPath() + " " + v.getMessage())
                .sorted()
                .toList();
    }

    public void send(ChatMessage message, String queue, List<String> violations) {
        String reason = String.join("; ", violations);

        if (message == null) {
            log.warn("Null payload rejected on queue '{}': {}", queue, reason);
            return;
        }

        rabbitTemplate.convertAndSend(
                properties.exchangeInvalid(),
                "messager.invalid",
                message,
                amqp -> annotate(amqp, queue, reason));

        log.warn("Message {} rejected as invalid on queue '{}': {}", message.getId(), queue, reason);
    }

    private Message annotate(Message amqp, String queue, String reason) {
        amqp.getMessageProperties().setHeader("x-messager-invalid-reason", reason);
        amqp.getMessageProperties().setHeader("x-messager-origin-queue", queue);
        amqp.getMessageProperties().setHeader("x-messager-rejected-at", Instant.now().toString());
        return amqp;
    }
}
