package com.integration.messager.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Messaging topology and delivery policy, driven from configuration so consumer
 * groups can be added without touching code.
 */
@ConfigurationProperties(prefix = "messager")
public record MessagerProperties(

        @DefaultValue("messager.exchange") String exchange,

        /**
         * Groups declared up front. Normally empty: a group is created when the
         * first receiver of it registers, so the topology follows the
         * participants instead of being fixed here.
         */
        @DefaultValue({}) List<String> groups,

        /** Routing key suffix that every group queue also listens on. */
        @DefaultValue("all") String broadcastKey,

        /** Queue that mirrors every message, for auditing / debugging. */
        @DefaultValue("messager.queue.audit") String auditQueue,

        /** How many concurrent consumers each group runs (competing consumers). */
        @DefaultValue("2") int concurrencyPerGroup,

        /** How many messages each in-memory channel keeps before dropping the oldest. */
        @DefaultValue("100") int historyLimit,

        /** What the broker does when a message cannot be handed to a receiver. */
        @DefaultValue Delivery delivery) {

    /** Queue-name prefix; everything after it is the group name. */
    public static final String QUEUE_PREFIX = "messager.queue.";

    /**
     * Names a group may not take, because they would collide with an
     * infrastructure queue or with the broadcast routing key.
     */
    private static final Set<String> RESERVED_GROUP_NAMES = Set.of("audit", "dlq", "invalid");

    /** Delivery policy for the broker -> receiver hop (lab objective 1.d). */
    public record Delivery(

            /** Attempts per receiver before the broker moves on to the next one. */
            @DefaultValue("3") int maxAttempts,

            /** Wait between attempts; doubles on every retry. */
            @DefaultValue("500ms") Duration backoff,

            /** How long one callback may take before it counts as failed. */
            @DefaultValue("3s") Duration timeout,

            /** How long the producer waits for the broker to confirm a publish. */
            @DefaultValue("5s") Duration confirmTimeout) {
    }

    public String exchangeDlx() {
        return exchange + ".dlx";
    }

    /** Invalid Message Channel: messages the broker understood but cannot accept. */
    public String exchangeInvalid() {
        return exchange + ".invalid";
    }

    public String dlqName() {
        return "messager.queue.dlq";
    }

    public String invalidQueue() {
        return "messager.queue.invalid";
    }

    public String queueFor(String group) {
        return QUEUE_PREFIX + group;
    }

    public String routingKeyFor(String target) {
        return "messager." + target;
    }

    /**
     * The group a queue belongs to, or empty for infrastructure queues (audit,
     * dlq, invalid) that are not owned by any group.
     */
    public Optional<String> groupOfQueue(String queue) {
        if (queue == null || !queue.startsWith(QUEUE_PREFIX)) {
            return Optional.empty();
        }
        String group = queue.substring(QUEUE_PREFIX.length());
        return isReservedGroupName(group) ? Optional.empty() : Optional.of(group);
    }

    public boolean isReservedGroupName(String group) {
        return RESERVED_GROUP_NAMES.contains(group) || broadcastKey.equals(group);
    }
}
