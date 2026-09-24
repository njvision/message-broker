package com.integration.simulator.receiver;

import com.integration.simulator.ChatMessage;
import com.integration.simulator.SimulatorProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "simulator.receiver.enabled", havingValue = "true")
public class ReceiverEndpoint {

    private final SimulatorProperties properties;
    private final BrokerRegistrar registrar;

    private final Deque<ChatMessage> received = new ConcurrentLinkedDeque<>();
    private final AtomicLong count = new AtomicLong();
    private final AtomicLong directlyAddressed = new AtomicLong();
    private volatile boolean healthy = true;

    @PostMapping("/receive")
    public ResponseEntity<Void> receive(
            @RequestBody ChatMessage message,
            @RequestHeader(value = "X-Messager-Group", required = false) String group,
            @RequestHeader(value = "X-Messager-Direct", required = false) String direct) {

        if (!healthy) {
            log.warn("Refusing message {} on purpose (receiver marked unhealthy)", message.getId());
            return ResponseEntity.status(503).build();
        }

        boolean addressedToMe = Boolean.parseBoolean(direct);
        log.info("Received message {} ({}) from '{}': {}",
                message.getId(),
                addressedToMe ? "addressed to me by name" : "as a member of group '" + group + "'",
                message.getSender(),
                message.getContent());

        received.addFirst(message);
        count.incrementAndGet();
        if (addressedToMe) {
            directlyAddressed.incrementAndGet();
        }
        while (received.size() > properties.receiver().historyLimit()) {
            received.pollLast();
        }
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/received")
    public Map<String, Object> received() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("name", registrar.name());
        view.put("addressAs", "@" + registrar.name());
        view.put("group", properties.receiver().group());
        view.put("healthy", healthy);
        view.put("total", count.get());
        view.put("directlyAddressed", directlyAddressed.get());
        view.put("messages", List.copyOf(received));
        return view;
    }

    @PostMapping("/fail")
    public Map<String, Object> startFailing() {
        healthy = false;
        log.warn("Receiver switched to failing");
        return Map.of("name", registrar.name(), "healthy", false);
    }

    @PostMapping("/heal")
    public Map<String, Object> heal() {
        healthy = true;
        log.info("Receiver switched back to healthy");
        return Map.of("name", registrar.name(), "healthy", true);
    }
}
