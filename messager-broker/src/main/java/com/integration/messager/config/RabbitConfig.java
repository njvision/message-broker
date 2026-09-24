package com.integration.messager.config;

import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.messaging.handler.annotation.support.DefaultMessageHandlerMethodFactory;
import org.springframework.messaging.handler.annotation.support.MessageHandlerMethodFactory;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;

@Configuration
@EnableConfigurationProperties(MessagerProperties.class)
public class RabbitConfig {

    private final MessagerProperties properties;

    public RabbitConfig(MessagerProperties properties) {
        this.properties = properties;
    }

    @Bean
    public TopicExchange messagerExchange() {
        return new TopicExchange(properties.exchange(), true, false);
    }

    @Bean
    public TopicExchange messagerDlxExchange() {
        return new TopicExchange(properties.exchangeDlx(), true, false);
    }

    @Bean
    public TopicExchange messagerInvalidExchange() {
        return new TopicExchange(properties.exchangeInvalid(), true, false);
    }

    @Bean
    public Declarables messagerTopology(TopicExchange messagerExchange,
                                        TopicExchange messagerDlxExchange,
                                        TopicExchange messagerInvalidExchange) {
        List<Declarable> declarables = new ArrayList<>();

        for (String group : properties.groups()) {
            Queue queue = QueueBuilder.durable(properties.queueFor(group))
                    // no dead-letter-routing-key: the original routing key is kept
                    .deadLetterExchange(properties.exchangeDlx())
                    .build();
            declarables.add(queue);
            // addressed to this group specifically
            declarables.add(BindingBuilder.bind(queue).to(messagerExchange)
                    .with(properties.routingKeyFor(group)));
            // addressed to everyone
            declarables.add(BindingBuilder.bind(queue).to(messagerExchange)
                    .with(properties.routingKeyFor(properties.broadcastKey())));
        }

        Queue audit = QueueBuilder.durable(properties.auditQueue()).build();
        declarables.add(audit);
        declarables.add(BindingBuilder.bind(audit).to(messagerExchange).with("messager.#"));

        Queue dlq = QueueBuilder.durable(properties.dlqName()).build();
        declarables.add(dlq);
        declarables.add(BindingBuilder.bind(dlq).to(messagerDlxExchange).with("#"));

        Queue invalid = QueueBuilder.durable(properties.invalidQueue()).build();
        declarables.add(invalid);
        declarables.add(BindingBuilder.bind(invalid).to(messagerInvalidExchange).with("#"));

        return new Declarables(declarables);
    }

    @Bean
    public MessageHandlerMethodFactory messageHandlerMethodFactory() {
        DefaultMessageHandlerMethodFactory factory = new DefaultMessageHandlerMethodFactory();
        factory.afterPropertiesSet();
        return factory;
    }

    @Bean
    public MessageConverter jsonMessageConverter(JsonMapper jsonMapper) {
        return new JacksonJsonMessageConverter(jsonMapper);
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        template.setExchange(properties.exchange());
        template.setMandatory(true);
        return template;
    }

    @Bean
    public RestClient receiverRestClient() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.delivery().timeout())
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.delivery().timeout());

        return RestClient.builder().requestFactory(requestFactory).build();
    }
}
