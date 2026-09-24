package com.integration.messager.routing;

import com.integration.messager.admin.GroupAdminService;
import com.integration.messager.config.MessagerProperties;
import com.integration.messager.delivery.ReceiverRegistration;
import com.integration.messager.delivery.ReceiverRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class MessageRouter {

    /** Header that carries the intended receiver through RabbitMQ. */
    public static final String RECIPIENT_HEADER = "x-messager-recipient";

    /** Header that says every member of the group must get a copy. */
    public static final String EVERY_MEMBER_HEADER = "x-messager-every-member";

    private final MessagerProperties properties;
    private final ReceiverRegistry receivers;
    private final GroupAdminService groupAdmin;

    public Route routeFor(MessageTarget target) {
        return switch (target.kind()) {
            case BROADCAST -> new Route(
                    properties.routingKeyFor(properties.broadcastKey()), null, false, target.describe());

            // same broadcast routing key; each group's consumer then fans the
            // copy it received out to its own members
            case BROADCAST_EVERY_MEMBER -> new Route(
                    properties.routingKeyFor(properties.broadcastKey()), null, true, target.describe());

            case GROUP -> {
                requireGroup(target.name());
                yield new Route(properties.routingKeyFor(target.name()), null, false, target.describe());
            }

            case GROUP_EVERY_MEMBER -> {
                requireGroup(target.name());
                yield new Route(properties.routingKeyFor(target.name()), null, true, target.describe());
            }

            case RECEIVER -> {
                Optional<ReceiverRegistration> receiver = receivers.findByName(target.name());
                if (receiver.isEmpty()) {
                    throw new UnknownTargetException(
                            "Unknown receiver '" + target.name() + "'. Registered receivers: "
                                    + receivers.names());
                }
                yield new Route(
                        properties.routingKeyFor(receiver.get().group()),
                        receiver.get().name(),
                        false,
                        target.describe());
            }
        };
    }

    private void requireGroup(String group) {
        if (!groupAdmin.exists(group)) {
            throw new UnknownTargetException(
                    "Unknown group '" + group + "'. Live groups: " + groupAdmin.groups());
        }
    }

    public record Route(String routingKey, String recipient, boolean everyMember, String description) {

        public boolean isDirect() {
            return recipient != null;
        }
    }

    /** The sender named a destination that does not exist. */
    public static class UnknownTargetException extends RuntimeException {

        public UnknownTargetException(String message) {
            super(message);
        }
    }
}
