package com.integration.messager.config;

import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListenerConfigurer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistrar;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.handler.annotation.support.MessageHandlerMethodFactory;

@Configuration
@RequiredArgsConstructor
public class RabbitListenerConfig implements RabbitListenerConfigurer {

    private final MessagerProperties properties;
    private final GroupListenerEndpoints endpoints;
    private final MessageHandlerMethodFactory messageHandlerMethodFactory;

    @Override
    public void configureRabbitListeners(RabbitListenerEndpointRegistrar registrar) {
        registrar.setMessageHandlerMethodFactory(messageHandlerMethodFactory);

        for (String group : properties.groups()) {
            registrar.registerEndpoint(endpoints.group(
                    GroupListenerEndpoints.endpointIdFor(group),
                    properties.queueFor(group),
                    properties.concurrencyPerGroup()));
        }

        registrar.registerEndpoint(endpoints.audit("messager-audit", properties.auditQueue()));
        registrar.registerEndpoint(endpoints.parked("messager-dlq", properties.dlqName()));
        registrar.registerEndpoint(endpoints.parked("messager-invalid", properties.invalidQueue()));
    }
}
