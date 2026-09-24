package com.integration.messager.producer;

import com.integration.messager.config.MessagerProperties;
import com.integration.messager.model.ChatMessage;
import com.integration.messager.routing.MessageRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
@RequiredArgsConstructor
public class MessageProducer {

    private final RabbitTemplate rabbitTemplate;
    private final MessagerProperties properties;

    public void send(ChatMessage message, MessageRouter.Route route) {
        log.info("Publishing to exchange '{}' with routing key '{}' ({}): {}",
                properties.exchange(), route.routingKey(), route.description(), message);

        CorrelationData correlation = new CorrelationData(message.getId());
        rabbitTemplate.convertAndSend(
                properties.exchange(),
                route.routingKey(),
                message,
                amqp -> markRecipient(amqp, route),
                correlation);

        awaitConfirm(correlation, message);
    }

    private Message markRecipient(Message amqp, MessageRouter.Route route) {
        if (route.isDirect()) {
            amqp.getMessageProperties().setHeader(MessageRouter.RECIPIENT_HEADER, route.recipient());
        }
        if (route.everyMember()) {
            amqp.getMessageProperties().setHeader(MessageRouter.EVERY_MEMBER_HEADER, Boolean.TRUE);
        }
        return amqp;
    }

    private void awaitConfirm(CorrelationData correlation, ChatMessage message) {
        long timeoutMillis = properties.delivery().confirmTimeout().toMillis();
        try {
            CorrelationData.Confirm confirm =
                    correlation.getFuture().get(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!confirm.isAck()) {
                throw new AmqpException("The agent refused message " + message.getId()
                        + ": " + confirm.getReason());
            }
        } catch (TimeoutException ex) {
            throw new AmqpException("The agent did not confirm message " + message.getId()
                    + " within " + timeoutMillis + "ms", ex);
        } catch (ExecutionException ex) {
            throw new AmqpException("Confirming message " + message.getId() + " failed", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AmqpException("Interrupted while confirming message " + message.getId(), ex);
        }
    }
}
