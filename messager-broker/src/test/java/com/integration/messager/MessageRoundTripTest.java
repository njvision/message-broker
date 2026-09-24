package com.integration.messager;

import com.integration.messager.admin.GroupAdminService;
import com.integration.messager.config.MessagerProperties;
import com.integration.messager.model.ChatMessage;
import com.integration.messager.producer.MessageProducer;
import com.integration.messager.routing.MessageRouter;
import com.integration.messager.routing.MessageTarget;
import com.integration.messager.store.MessageStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
class MessageRoundTripTest extends RabbitContainerSupport {

    @Autowired
    MessageProducer producer;

    @Autowired
    MessageRouter router;

    @Autowired
    MessageStore store;

    @Autowired
    MessagerProperties properties;

    @Autowired
    GroupAdminService groupAdmin;

    @BeforeEach
    void ensureGroups() {
        groupAdmin.ensureGroup("alpha");
        groupAdmin.ensureGroup("beta");
    }

    private ChatMessage newMessage() {
        return ChatMessage.builder()
                .id(UUID.randomUUID().toString())
                .sender("alice")
                .content("hello rabbit")
                .sentAt(Instant.now())
                .build();
    }

    private void send(ChatMessage message, String target) {
        producer.send(message, router.routeFor(
                MessageTarget.parse(target, properties.broadcastKey())));
    }

    @Test
    void messageAddressedToOneGroupReachesOnlyThatGroup() {
        ChatMessage sent = newMessage();

        send(sent, "alpha");

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(store.snapshot(properties.queueFor("alpha")))
                        .extracting(ChatMessage::getId)
                        .contains(sent.getId()));

        assertThat(store.snapshot(properties.queueFor("beta")))
                .extracting(ChatMessage::getId)
                .doesNotContain(sent.getId());
    }

    @Test
    void broadcastReachesEveryGroup() {
        ChatMessage sent = newMessage();

        send(sent, properties.broadcastKey());

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            for (String group : groupAdmin.groups()) {
                assertThat(store.snapshot(properties.queueFor(group)))
                        .extracting(ChatMessage::getId)
                        .contains(sent.getId());
            }
        });
    }

    @Test
    void auditQueueSeesEveryMessage() {
        ChatMessage sent = newMessage();

        send(sent, "beta");

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(store.snapshot(properties.auditQueue()))
                        .extracting(ChatMessage::getId)
                        .contains(sent.getId()));
    }

    @Test
    void messageThatFailsValidationGoesToTheInvalidChannel() {
        ChatMessage invalid = ChatMessage.builder()
                .id(UUID.randomUUID().toString())
                .content("who sent this?")
                .sentAt(Instant.now())
                .build();

        send(invalid, "alpha");

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(store.snapshot(properties.invalidQueue()))
                        .extracting(ChatMessage::getId)
                        .contains(invalid.getId()));

        assertThat(store.snapshot(properties.queueFor("alpha")))
                .extracting(ChatMessage::getId)
                .doesNotContain(invalid.getId());
    }

    @Test
    void unknownTargetIsRejectedBeforeAnythingIsPublished() {
        MessageTarget target = MessageTarget.parse("nobody-here", properties.broadcastKey());

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> router.routeFor(target)))
                .isInstanceOf(MessageRouter.UnknownTargetException.class);
    }
}
