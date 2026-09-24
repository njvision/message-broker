package com.integration.messager.config;

import com.integration.messager.consumer.GroupMessageConsumer;
import com.integration.messager.model.ChatMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.listener.MethodRabbitListenerEndpoint;
import org.springframework.messaging.handler.annotation.support.MessageHandlerMethodFactory;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

@Component
@RequiredArgsConstructor
public class GroupListenerEndpoints {

    // the group handler takes two arguments more: how the sender addressed it
    private static final Method GROUP_HANDLER =
            handler("receive", ChatMessage.class, String.class, String.class, String.class, Boolean.class);
    private static final Method AUDIT_HANDLER =
            handler("audit", ChatMessage.class, String.class, String.class);
    private static final Method PARKED_HANDLER =
            handler("parked", ChatMessage.class, String.class, String.class);

    private static Method handler(String name, Class<?>... parameterTypes) {
        try {
            return GroupMessageConsumer.class.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private final GroupMessageConsumer consumer;
    private final MessageHandlerMethodFactory messageHandlerMethodFactory;

    public static String endpointIdFor(String group) {
        return "messager-group-" + group;
    }

    /** A consumer group: validation, storage and delivery to receivers. */
    public MethodRabbitListenerEndpoint group(String id, String queue, int concurrency) {
        return create(id, queue, concurrency, GROUP_HANDLER);
    }

    /** The audit queue: mirrors everything, delivers nothing. */
    public MethodRabbitListenerEndpoint audit(String id, String queue) {
        return create(id, queue, 1, AUDIT_HANDLER);
    }

    /** Dead letter / invalid message queues: drained into the store for inspection. */
    public MethodRabbitListenerEndpoint parked(String id, String queue) {
        return create(id, queue, 1, PARKED_HANDLER);
    }

    private MethodRabbitListenerEndpoint create(String id, String queue, int concurrency, Method handler) {
        MethodRabbitListenerEndpoint endpoint = new MethodRabbitListenerEndpoint();
        endpoint.setId(id);
        endpoint.setQueueNames(queue);
        endpoint.setBean(consumer);
        endpoint.setMethod(handler);
        endpoint.setMessageHandlerMethodFactory(messageHandlerMethodFactory);
        endpoint.setConcurrency(String.valueOf(concurrency));
        return endpoint;
    }
}
