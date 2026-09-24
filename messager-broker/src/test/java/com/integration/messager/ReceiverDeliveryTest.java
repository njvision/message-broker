package com.integration.messager;

import com.integration.messager.admin.GroupAdminService;
import com.integration.messager.config.MessagerProperties;
import com.integration.messager.delivery.ReceiverRegistry;
import com.integration.messager.model.ChatMessage;
import com.integration.messager.producer.MessageProducer;
import com.integration.messager.routing.MessageRouter;
import com.integration.messager.routing.MessageTarget;
import com.integration.messager.store.MessageStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReceiverDeliveryTest extends RabbitContainerSupport {

    @LocalServerPort
    int port;

    @Autowired
    MessageProducer producer;

    @Autowired
    MessageRouter router;

    @Autowired
    MessageStore store;

    @Autowired
    MessagerProperties properties;

    @Autowired
    ReceiverRegistry registry;

    @Autowired
    GroupAdminService groupAdmin;

    @Autowired
    StubReceiver first;

    @Autowired
    SecondStubReceiver second;

    @Autowired
    ThirdStubReceiver third;

    @AfterEach
    void reset() {
        registry.all().forEach(r -> registry.unregister(r.id()));
        first.received.clear();
        first.failing = false;
        second.received.clear();
        third.received.clear();
    }

    private ChatMessage newMessage() {
        return ChatMessage.builder()
                .id(UUID.randomUUID().toString())
                .sender("alice")
                .content("hello receiver")
                .sentAt(Instant.now())
                .build();
    }

    private void send(ChatMessage message, String target) {
        producer.send(message, router.routeFor(
                MessageTarget.parse(target, properties.broadcastKey())));
    }

    private void register(String name, String group, String path) {
        groupAdmin.ensureGroup(group);
        registry.register(name, group, "http://localhost:" + port + path);
    }

    private void registerBoth(String group) {
        register("first", group, "/stub-receiver");
        register("second", group, "/second-receiver");
    }

    @Test
    void registeredReceiverGetsTheMessagePushedToIt() {
        register("first", "team", "/stub-receiver");
        ChatMessage sent = newMessage();

        send(sent, "team");

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(first.received)
                        .extracting(ChatMessage::getId)
                        .contains(sent.getId()));
    }

    @Test
    void messageAddressedToOneReceiverGoesOnlyToThatOne() {
        registerBoth("team");
        ChatMessage sent = newMessage();

        send(sent, "@second");

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(second.received)
                        .extracting(ChatMessage::getId)
                        .contains(sent.getId()));

        assertThat(first.received)
                .extracting(ChatMessage::getId)
                .doesNotContain(sent.getId());
    }

    @Test
    void addressingOneReceiverDoesNotFailOverToItsGroup() {
        registerBoth("team");
        first.failing = true;
        ChatMessage sent = newMessage();

        send(sent, "@first");

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(store.snapshot(properties.dlqName()))
                        .extracting(ChatMessage::getId)
                        .contains(sent.getId()));

        assertThat(second.received)
                .extracting(ChatMessage::getId)
                .doesNotContain(sent.getId());
    }

    @Test
    void groupDeliveryDoesFailOverToTheOtherReceiver() {
        registerBoth("team");
        first.failing = true;
        ChatMessage sent = newMessage();

        send(sent, "team");

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(second.received)
                        .extracting(ChatMessage::getId)
                        .contains(sent.getId()));
    }

    @Test
    void messageForEveryMemberReachesAllOfThemAndNobodyElse() {
        registerBoth("team");
        register("outsider", "other-team", "/third-receiver");
        ChatMessage sent = newMessage();

        send(sent, "every:team");

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(first.received).extracting(ChatMessage::getId).contains(sent.getId());
            assertThat(second.received).extracting(ChatMessage::getId).contains(sent.getId());
        });

        assertThat(third.received)
                .extracting(ChatMessage::getId)
                .doesNotContain(sent.getId());
    }

    @Test
    void everyMemberOfEveryGroupReachesAllThreeReceivers() {
        registerBoth("team");
        register("outsider", "other-team", "/third-receiver");
        ChatMessage sent = newMessage();

        send(sent, "every:" + properties.broadcastKey());

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(first.received).extracting(ChatMessage::getId).contains(sent.getId());
            assertThat(second.received).extracting(ChatMessage::getId).contains(sent.getId());
            assertThat(third.received).extracting(ChatMessage::getId).contains(sent.getId());
        });
    }

    @Test
    void plainBroadcastStillReachesOnlyOneMemberPerGroup() {
        registerBoth("team");
        register("outsider", "other-team", "/third-receiver");
        ChatMessage sent = newMessage();

        send(sent, properties.broadcastKey());

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(third.received).extracting(ChatMessage::getId).contains(sent.getId()));

        assertThat(first.received.size() + second.received.size()).isEqualTo(1);
    }

    @Test
    void ordinaryGroupSendStillReachesOnlyOneMember() {
        registerBoth("team");
        ChatMessage sent = newMessage();

        send(sent, "team");

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(first.received.size() + second.received.size()).isEqualTo(1));
    }

    @Test
    void messageForAGroupWithNoReceiversIsDeadLetteredRatherThanDropped() {
        register("first", "lonely", "/stub-receiver");
        registry.unregister("first");
        ChatMessage sent = newMessage();

        send(sent, "lonely");

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(store.snapshot(properties.dlqName()))
                        .extracting(ChatMessage::getId)
                        .contains(sent.getId()));
    }

    @TestConfiguration
    static class StubReceiverConfiguration {

        @Bean
        StubReceiver stubReceiver() {
            return new StubReceiver();
        }

        @Bean
        SecondStubReceiver secondStubReceiver() {
            return new SecondStubReceiver();
        }

        @Bean
        ThirdStubReceiver thirdStubReceiver() {
            return new ThirdStubReceiver();
        }
    }

    @RestController
    static class StubReceiver {

        final List<ChatMessage> received = new CopyOnWriteArrayList<>();
        volatile boolean failing;

        @PostMapping("/stub-receiver")
        ResponseEntity<Void> receive(@RequestBody ChatMessage message) {
            if (failing) {
                return ResponseEntity.internalServerError().build();
            }
            received.add(message);
            return ResponseEntity.accepted().build();
        }
    }

    @RestController
    static class ThirdStubReceiver {

        final List<ChatMessage> received = new CopyOnWriteArrayList<>();

        @PostMapping("/third-receiver")
        ResponseEntity<Void> receive(@RequestBody ChatMessage message) {
            received.add(message);
            return ResponseEntity.accepted().build();
        }
    }

    @RestController
    static class SecondStubReceiver {

        final List<ChatMessage> received = new CopyOnWriteArrayList<>();

        @PostMapping("/second-receiver")
        ResponseEntity<Void> receive(@RequestBody ChatMessage message) {
            received.add(message);
            return ResponseEntity.accepted().build();
        }
    }
}
