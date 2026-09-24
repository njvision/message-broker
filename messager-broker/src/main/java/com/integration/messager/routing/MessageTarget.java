package com.integration.messager.routing;

public record MessageTarget(Kind kind, String name) {

    public enum Kind {
        /** Every group gets its own copy; one member of each takes it. */
        BROADCAST,
        /** Every receiver of every group gets its own copy. */
        BROADCAST_EVERY_MEMBER,
        /** One group; which of its receivers takes it is the agent's choice. */
        GROUP,
        /** Every receiver of one group gets its own copy. */
        GROUP_EVERY_MEMBER,
        /** One named receiver, and no other. */
        RECEIVER
    }

    private static final String GROUP_PREFIX = "group:";
    private static final String EVERY_PREFIX = "every:";
    private static final String EVERY_SUFFIX = ":*";
    private static final String RECEIVER_PREFIX = "receiver:";
    private static final String RECEIVER_MARK = "@";

    public static MessageTarget parse(String raw, String broadcastKey) {
        if (raw == null || raw.isBlank()) {
            return new MessageTarget(Kind.BROADCAST, broadcastKey);
        }

        String target = raw.trim();
        if (target.equals(broadcastKey)) {
            return new MessageTarget(Kind.BROADCAST, broadcastKey);
        }
        if (target.startsWith(RECEIVER_PREFIX)) {
            return new MessageTarget(Kind.RECEIVER, target.substring(RECEIVER_PREFIX.length()));
        }
        if (target.startsWith(RECEIVER_MARK)) {
            return new MessageTarget(Kind.RECEIVER, target.substring(RECEIVER_MARK.length()));
        }
        if (target.startsWith(EVERY_PREFIX)) {
            return everyMemberOf(target.substring(EVERY_PREFIX.length()), broadcastKey);
        }
        if (target.endsWith(EVERY_SUFFIX)) {
            return everyMemberOf(target.substring(0, target.length() - EVERY_SUFFIX.length()), broadcastKey);
        }
        if (target.startsWith(GROUP_PREFIX)) {
            return new MessageTarget(Kind.GROUP, target.substring(GROUP_PREFIX.length()));
        }
        return new MessageTarget(Kind.GROUP, target);
    }

    private static MessageTarget everyMemberOf(String name, String broadcastKey) {
        boolean allGroups = name.equals(broadcastKey) || name.equals("*") || name.isBlank();
        return allGroups
                ? new MessageTarget(Kind.BROADCAST_EVERY_MEMBER, broadcastKey)
                : new MessageTarget(Kind.GROUP_EVERY_MEMBER, name);
    }

    public String describe() {
        return switch (kind) {
            case BROADCAST -> "every group";
            case BROADCAST_EVERY_MEMBER -> "every receiver of every group";
            case GROUP -> "group '" + name + "'";
            case GROUP_EVERY_MEMBER -> "every member of group '" + name + "'";
            case RECEIVER -> "receiver '" + name + "'";
        };
    }
}
