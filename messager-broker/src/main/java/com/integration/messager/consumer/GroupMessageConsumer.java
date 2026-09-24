package com.integration.messager.consumer;

import com.integration.messager.config.MessagerProperties;
import com.integration.messager.delivery.DeliveryResult;
import com.integration.messager.delivery.DeliveryService;
import com.integration.messager.model.ChatMessage;
import com.integration.messager.policy.InvalidMessageChannel;
import com.integration.messager.routing.MessageRouter;
import com.integration.messager.store.MessageStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class GroupMessageConsumer {

    private final MessagerProperties properties;
    private final MessageStore store;
    private final InvalidMessageChannel invalidMessageChannel;
    private final DeliveryService deliveryService;

    public void receive(ChatMessage message,
                        @Header(AmqpHeaders.CONSUMER_QUEUE) String queue,
                        @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String routingKey,
                        @Header(name = MessageRouter.RECIPIENT_HEADER, required = false) String recipient,
                        @Header(name = MessageRouter.EVERY_MEMBER_HEADER, required = false) Boolean everyMember) {

        List<String> violations = invalidMessageChannel.validate(message);
        if (!violations.isEmpty()) {
            invalidMessageChannel.send(message, queue, violations);
            return;
        }

        boolean toEveryMember = Boolean.TRUE.equals(everyMember);
        log.info("[{}] received (routingKey={}{}): {}", queue, routingKey,
                recipient != null ? ", for @" + recipient : toEveryMember ? ", for every member" : "",
                message);
        store.record(queue, message);

        Optional<String> group = properties.groupOfQueue(queue);
        if (group.isEmpty()) {
            log.warn("Queue '{}' is not owned by a group; nothing to deliver to", queue);
            return;
        }

        DeliveryResult result = deliveryService.deliver(group.get(), message, recipient, toEveryMember);
        log.debug("Message {} on group '{}': {}", message.getId(), group.get(), result.status());
    }

    public void audit(ChatMessage message,
                      @Header(AmqpHeaders.CONSUMER_QUEUE) String queue,
                      @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String routingKey) {

        log.debug("[{}] audit copy (routingKey={}): {}", queue, routingKey, message);
        store.record(queue, message);
    }

    public void parked(ChatMessage message,
                       @Header(AmqpHeaders.CONSUMER_QUEUE) String queue,
                       @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String routingKey) {

        log.info("[{}] parked message (routingKey={}): {}", queue, routingKey, message);
        store.record(queue, message);
    }
}
