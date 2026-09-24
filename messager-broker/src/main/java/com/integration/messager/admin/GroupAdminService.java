package com.integration.messager.admin;

import com.integration.messager.config.GroupListenerEndpoints;
import com.integration.messager.config.MessagerProperties;
import com.integration.messager.delivery.ReceiverRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.MessageListenerContainer;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.listener.RabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.Lifecycle;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class GroupAdminService {

    private static final Pattern VALID_GROUP_NAME = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}");

    private final MessagerProperties properties;
    private final AmqpAdmin amqpAdmin;
    private final RabbitListenerEndpointRegistry registry;
    private final GroupListenerEndpoints endpoints;
    private final TopicExchange messagerExchange;
    private final ReceiverRegistry receivers;

    @Qualifier("rabbitListenerContainerFactory")
    private final RabbitListenerContainerFactory<?> containerFactory;

    private final Set<String> liveGroups = new CopyOnWriteArraySet<>();

    @PostConstruct
    void seedFromConfiguration() {
        liveGroups.addAll(properties.groups());
    }

    public Set<String> groups() {
        return Set.copyOf(liveGroups);
    }

    public boolean exists(String group) {
        return liveGroups.contains(group);
    }

    public synchronized void ensureGroup(String group) {
        requireValidName(group);
        if (!liveGroups.contains(group)) {
            addGroup(group, properties.concurrencyPerGroup());
        }
    }

    public void addGroup(String group, int concurrency) {
        requireValidName(group);
        if (liveGroups.contains(group)) {
            throw new IllegalStateException("Group '" + group + "' already exists");
        }

        amqpAdmin.declareQueue(queueFor(group));
        bindingsFor(group).forEach(amqpAdmin::declareBinding);

        registry.registerListenerContainer(
                endpoints.group(GroupListenerEndpoints.endpointIdFor(group),
                        properties.queueFor(group), concurrency),
                containerFactory,
                true);

        liveGroups.add(group);
        log.info("Group '{}' added with {} consumer(s) on queue '{}'",
                group, concurrency, properties.queueFor(group));
    }

    public void removeGroup(String group, boolean deleteQueue) {
        requireExisting(group);

        MessageListenerContainer container =
                registry.unregisterListenerContainer(GroupListenerEndpoints.endpointIdFor(group));
        if (container instanceof Lifecycle lifecycle) {
            lifecycle.stop();
        }

        bindingsFor(group).forEach(amqpAdmin::removeBinding);
        if (deleteQueue) {
            amqpAdmin.deleteQueue(properties.queueFor(group));
        }

        int droppedReceivers = receivers.unregisterGroup(group);
        liveGroups.remove(group);
        log.info("Group '{}' removed (queue {}, {} receiver(s) dropped)",
                group, deleteQueue ? "deleted" : "kept", droppedReceivers);
    }

    public int setConcurrency(String group, int consumers) {
        requireExisting(group);
        if (consumers < 1) {
            throw new IllegalArgumentException("A group needs at least one consumer");
        }

        MessageListenerContainer container =
                registry.getListenerContainer(GroupListenerEndpoints.endpointIdFor(group));
        if (!(container instanceof SimpleMessageListenerContainer simple)) {
            throw new IllegalStateException("Group '" + group + "' has no adjustable container");
        }

        // max must move first, otherwise setConcurrentConsumers rejects the value
        simple.setMaxConcurrentConsumers(consumers);
        simple.setConcurrentConsumers(consumers);

        log.info("Group '{}' now runs {} consumer(s)", group, consumers);
        return consumers;
    }

    public int concurrencyOf(String group) {
        MessageListenerContainer container =
                registry.getListenerContainer(GroupListenerEndpoints.endpointIdFor(group));
        return container instanceof SimpleMessageListenerContainer simple
                ? simple.getActiveConsumerCount()
                : 0;
    }

    private void requireExisting(String group) {
        if (!liveGroups.contains(group)) {
            throw new IllegalArgumentException("Unknown group '" + group + "'");
        }
    }

    private void requireValidName(String group) {
        if (group == null || !VALID_GROUP_NAME.matcher(group).matches()) {
            throw new IllegalArgumentException(
                    "A group name must match " + VALID_GROUP_NAME.pattern());
        }
        if (properties.isReservedGroupName(group)) {
            throw new IllegalArgumentException("'" + group + "' is reserved and cannot be a group name");
        }
    }

    private Queue queueFor(String group) {
        return QueueBuilder.durable(properties.queueFor(group))
                .deadLetterExchange(properties.exchangeDlx())
                .build();
    }

    private List<Binding> bindingsFor(String group) {
        Queue queue = queueFor(group);
        return List.of(
                BindingBuilder.bind(queue).to(messagerExchange).with(properties.routingKeyFor(group)),
                BindingBuilder.bind(queue).to(messagerExchange)
                        .with(properties.routingKeyFor(properties.broadcastKey())));
    }
}
