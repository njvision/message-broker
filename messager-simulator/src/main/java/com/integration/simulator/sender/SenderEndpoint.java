package com.integration.simulator.sender;

import com.integration.simulator.SimulatorProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The sending participant. One of them, able to address anything.
 *
 * <p>It knows the agent's address and a logical destination - never which
 * receiver will end up with the message, nor how many there are. The
 * destination is chosen per message:
 *
 * <ul>
 *   <li>{@code POST /send?target=workers} - the group {@code workers}, one of
 *       its receivers takes it;</li>
 *   <li>{@code POST /send?target=@r2} - the receiver named {@code r2}, and no
 *       other;</li>
 *   <li>{@code POST /send?target=all} - every group gets a copy.</li>
 * </ul>
 *
 * <p>{@code GET /targets} asks the agent what can be addressed right now, so
 * nothing has to be known in advance.
 */
@Slf4j
@RestController
@Component
@ConditionalOnProperty(name = "simulator.sender.enabled", havingValue = "true")
public class SenderEndpoint {

    private final SimulatorProperties properties;
    private final RestClient restClient;
    private final AtomicLong sequence = new AtomicLong();

    public SenderEndpoint(SimulatorProperties properties, RestClient brokerRestClient) {
        this.properties = properties;
        this.restClient = brokerRestClient;
    }

    /** What the agent can be asked to deliver to, right now. */
    @GetMapping("/targets")
    public Object targets() {
        return restClient.get()
                .uri(properties.brokerUrl() + "/api/messages/targets")
                .retrieve()
                .body(Object.class);
    }

    /** Keeps traffic flowing without anyone poking the API; off by default. */
    @Scheduled(fixedDelayString = "${simulator.sender.interval:5s}")
    void sendOnTimer() {
        if (properties.sender().auto()) {
            send(properties.sender().defaultTarget(), null);
        }
    }

    /**
     * @param target a group name, {@code @name} for one receiver, or the
     *               broadcast key; falls back to the configured default
     */
    @PostMapping("/send")
    public Map<String, Object> send(
            @RequestParam(required = false) String target,
            @RequestParam(required = false) String content) {

        String destination = target == null || target.isBlank()
                ? properties.sender().defaultTarget()
                : target;

        long n = sequence.incrementAndGet();
        String body = content == null || content.isBlank()
                ? "message #" + n + " from " + properties.sender().name()
                : content;

        return publish(destination, body);
    }

    /** Same as {@code /send?target=@name}, spelled out for readability. */
    @PostMapping("/send/to/{receiver}")
    public Map<String, Object> sendToReceiver(
            @PathVariable String receiver,
            @RequestParam(required = false) String content) {
        return send("@" + receiver, content);
    }

    /** Same as {@code /send?target=<group>}, spelled out for readability. */
    @PostMapping("/send/group/{group}")
    public Map<String, Object> sendToGroup(
            @PathVariable String group,
            @RequestParam(required = false) String content) {
        return send(group, content);
    }

    /**
     * Every member of the group, not just one of them. Same as
     * {@code /send?target=every:<group>}.
     */
    @PostMapping("/send/everyone/{group}")
    public Map<String, Object> sendToEveryMember(
            @PathVariable String group,
            @RequestParam(required = false) String content) {
        return send("every:" + group, content);
    }

    private Map<String, Object> publish(String destination, String body) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("target", destination);
        result.put("content", body);

        try {
            Map<?, ?> accepted = restClient.post()
                    .uri(properties.brokerUrl() + "/api/messages")
                    .body(Map.of(
                            "sender", properties.sender().name(),
                            "content", body,
                            "target", destination))
                    .retrieve()
                    .body(Map.class);

            String id = accepted == null ? null : String.valueOf(accepted.get("id"));
            log.info("Sent message {} to '{}' ({})", id, destination,
                    accepted == null ? "?" : accepted.get("addressedTo"));
            result.put("accepted", true);
            result.put("id", id);
            result.put("addressedTo", accepted == null ? null : accepted.get("addressedTo"));
        } catch (RestClientResponseException ex) {
            HttpStatusCode status = ex.getStatusCode();
            log.error("The agent rejected the message ({}): {}", status, ex.getResponseBodyAsString());
            result.put("accepted", false);
            result.put("status", status.value());
            result.put("error", ex.getResponseBodyAsString());
        } catch (Exception ex) {
            log.error("Could not reach the agent at {}: {}", properties.brokerUrl(), ex.getMessage());
            result.put("accepted", false);
            result.put("error", ex.getMessage());
        }
        return result;
    }
}
