package com.integration.messager.web;

import com.integration.messager.admin.GroupAdminService;
import com.integration.messager.config.MessagerProperties;
import com.integration.messager.delivery.ReceiverRegistry;
import com.integration.messager.model.ChatMessage;
import com.integration.messager.producer.MessageProducer;
import com.integration.messager.routing.MessageRouter;
import com.integration.messager.routing.MessageTarget;
import com.integration.messager.store.BoundedMessageChannel;
import com.integration.messager.store.MessageStore;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.AmqpException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/messages")
@RequiredArgsConstructor
public class MessageController {

    private final MessageProducer producer;
    private final MessageRouter router;
    private final MessageStore store;
    private final MessagerProperties properties;
    private final GroupAdminService groupAdmin;
    private final ReceiverRegistry receivers;

    @PostMapping
    public ResponseEntity<Map<String, Object>> send(@Valid @RequestBody SendRequest request) {
        MessageTarget target = MessageTarget.parse(request.getTarget(), properties.broadcastKey());

        MessageRouter.Route route;
        try {
            route = router.routeFor(target);
        } catch (MessageRouter.UnknownTargetException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }

        ChatMessage message = ChatMessage.builder()
                .id(UUID.randomUUID().toString())
                .sender(request.getSender())
                .content(request.getContent())
                .sentAt(Instant.now())
                .build();

        try {
            producer.send(message, route);
        } catch (AmqpException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The message agent is unavailable; the message was not accepted", ex);
        }

        Map<String, Object> accepted = new LinkedHashMap<>();
        accepted.put("id", message.getId());
        accepted.put("sender", message.getSender());
        accepted.put("content", message.getContent());
        accepted.put("sentAt", message.getSentAt());
        accepted.put("addressedTo", route.description());
        accepted.put("routingKey", route.routingKey());
        return ResponseEntity.accepted().body(accepted);
    }

    @GetMapping("/targets")
    public Map<String, Object> targets() {
        Map<String, Object> targets = new LinkedHashMap<>();
        targets.put("broadcast", properties.broadcastKey());
        targets.put("groups", groupAdmin.groups().stream().sorted().toList());
        targets.put("receivers", receivers.all().stream()
                .sorted((a, b) -> a.name().compareTo(b.name()))
                .map(r -> Map.of("name", r.name(), "group", r.group(), "addressAs", "@" + r.name()))
                .toList());
        return targets;
    }

    @GetMapping("/groups")
    public Map<String, Object> groups() {
        return Map.of(
                "exchange", properties.exchange(),
                "groups", groupAdmin.groups(),
                "broadcastKey", properties.broadcastKey(),
                "concurrencyPerGroup", properties.concurrencyPerGroup());
    }

    @GetMapping
    public Map<String, List<ChatMessage>> received() {
        return store.snapshotAll();
    }

    @GetMapping("/channels")
    public List<BoundedMessageChannel.Stats> channels() {
        return store.stats();
    }

    @GetMapping("/dead-letters")
    public List<ChatMessage> deadLetters() {
        return store.snapshot(properties.dlqName());
    }

    @GetMapping("/invalid")
    public List<ChatMessage> invalid() {
        return store.snapshot(properties.invalidQueue());
    }

    @GetMapping("/{group}")
    public List<ChatMessage> receivedBy(@PathVariable String group) {
        if (!groupAdmin.exists(group)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown group '" + group + "'");
        }
        return store.snapshot(properties.queueFor(group));
    }
}
