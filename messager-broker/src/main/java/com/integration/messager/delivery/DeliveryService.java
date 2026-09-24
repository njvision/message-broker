package com.integration.messager.delivery;

import com.integration.messager.config.MessagerProperties;
import com.integration.messager.model.ChatMessage;
import com.integration.messager.policy.DeadLetterChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class DeliveryService {

    private final MessagerProperties properties;
    private final ReceiverRegistry registry;
    private final DeadLetterChannel deadLetterChannel;
    private final RestClient restClient;

    public DeliveryService(MessagerProperties properties,
                           ReceiverRegistry registry,
                           DeadLetterChannel deadLetterChannel,
                           RestClient receiverRestClient) {
        this.properties = properties;
        this.registry = registry;
        this.deadLetterChannel = deadLetterChannel;
        this.restClient = receiverRestClient;
    }

    public DeliveryResult deliver(String group, ChatMessage message, String recipient, boolean everyMember) {
        if (recipient != null) {
            return deliverToReceiver(group, message, recipient);
        }
        return everyMember
                ? deliverToEveryMember(group, message)
                : deliverToGroup(group, message);
    }

    private DeliveryResult deliverToEveryMember(String group, ChatMessage message) {
        List<ReceiverRegistration> members = registry.forGroup(group);

        if (members.isEmpty()) {
            log.warn("No receiver registered for group '{}'; dead-lettering message {}", group, message.getId());
            deadLetterChannel.send(message, group, "no receiver registered for group '" + group + "'");
            return DeliveryResult.noReceiver(group);
        }

        List<String> delivered = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        for (ReceiverRegistration receiver : members) {
            Optional<String> error = attempt(receiver, message, true);
            if (error.isEmpty()) {
                receiver.recordSuccess(true);
                delivered.add(receiver.name());
            } else {
                receiver.recordFailure(error.get());
                failures.add(receiver.name() + " (" + receiver.callbackUrl() + ") -> " + error.get());
            }
        }

        if (failures.isEmpty()) {
            log.info("Message {} delivered to every member of group '{}': {}",
                    message.getId(), group, delivered);
            return DeliveryResult.deliveredToEveryMember(group, delivered);
        }

        String reason = "not every member of '" + group + "' could take it: " + String.join("; ", failures);
        deadLetterChannel.send(message, group, reason);

        if (delivered.isEmpty()) {
            log.error("Message {}: no member of group '{}' took it: {}", message.getId(), group, failures);
            return DeliveryResult.deadLettered(group, failures);
        }

        log.warn("Message {} reached {} of {} members of group '{}'; the rest are dead-lettered: {}",
                message.getId(), delivered.size(), members.size(), group, failures);
        return DeliveryResult.partiallyDelivered(group, delivered, failures);
    }

    private DeliveryResult deliverToReceiver(String group, ChatMessage message, String recipient) {
        Optional<ReceiverRegistration> addressed = registry.findByName(recipient);

        if (addressed.isEmpty()) {
            // it unregistered between the publish and now
            String reason = "receiver '" + recipient + "' is no longer registered";
            log.warn("Message {}: {}", message.getId(), reason);
            deadLetterChannel.send(message, group, reason);
            return DeliveryResult.noReceiver(group);
        }

        ReceiverRegistration receiver = addressed.get();
        Optional<String> error = attempt(receiver, message, true);
        if (error.isEmpty()) {
            receiver.recordSuccess(true);
            log.info("Message {} delivered directly to receiver '{}'", message.getId(), recipient);
            return DeliveryResult.delivered(group, receiver);
        }

        receiver.recordFailure(error.get());
        String reason = "addressed to '" + recipient + "' -> " + error.get();
        log.error("Message {} could not be delivered: {}", message.getId(), reason);
        deadLetterChannel.send(message, group, reason);
        return DeliveryResult.deadLettered(group, List.of(reason));
    }

    private DeliveryResult deliverToGroup(String group, ChatMessage message) {
        List<ReceiverRegistration> candidates = registry.candidatesFor(group);

        if (candidates.isEmpty()) {
            log.warn("No receiver registered for group '{}'; dead-lettering message {}", group, message.getId());
            deadLetterChannel.send(message, group, "no receiver registered for group '" + group + "'");
            return DeliveryResult.noReceiver(group);
        }

        List<String> failures = new ArrayList<>();
        for (ReceiverRegistration receiver : candidates) {
            Optional<String> error = attempt(receiver, message, false);
            if (error.isEmpty()) {
                receiver.recordSuccess(false);
                log.info("Message {} delivered to receiver '{}' of group '{}'",
                        message.getId(), receiver.name(), group);
                return DeliveryResult.delivered(group, receiver);
            }
            receiver.recordFailure(error.get());
            failures.add(receiver.name() + " (" + receiver.callbackUrl() + ") -> " + error.get());
        }

        log.error("Message {} could not be delivered to any receiver of group '{}': {}",
                message.getId(), group, failures);
        deadLetterChannel.send(message, group, String.join("; ", failures));
        return DeliveryResult.deadLettered(group, failures);
    }

    private Optional<String> attempt(ReceiverRegistration receiver, ChatMessage message, boolean direct) {
        MessagerProperties.Delivery policy = properties.delivery();
        Duration backoff = policy.backoff();
        String lastError = "not attempted";

        for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
            try {
                restClient.post()
                        .uri(receiver.callbackUrl())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Messager-Group", receiver.group())
                        .header("X-Messager-Receiver", receiver.name())
                        .header("X-Messager-Direct", Boolean.toString(direct))
                        .body(message)
                        .retrieve()
                        .toBodilessEntity();
                return Optional.empty();
            } catch (Exception ex) {
                lastError = ex.getClass().getSimpleName() + ": " + ex.getMessage();
                log.warn("Attempt {}/{} to '{}' ({}) failed: {}",
                        attempt, policy.maxAttempts(), receiver.name(), receiver.callbackUrl(), lastError);
            }

            if (attempt < policy.maxAttempts() && !sleep(backoff)) {
                return Optional.of(lastError + " (retries interrupted)");
            }
            backoff = backoff.multipliedBy(2);
        }
        return Optional.of(lastError);
    }

    private boolean sleep(Duration backoff) {
        try {
            Thread.sleep(backoff.toMillis());
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
