package com.integration.messager.delivery;

import java.util.List;

public record DeliveryResult(Status status, String group, List<String> receivers, List<String> failures) {

    public enum Status {
        /** Everyone who was supposed to get it, got it. */
        DELIVERED,
        /** Some members of the group took it, some did not; the rest was dead-lettered. */
        PARTIALLY_DELIVERED,
        /** Nobody is registered for the group; the message went to the dead letter channel. */
        NO_RECEIVER,
        /** Every receiver failed; the message went to the dead letter channel. */
        DEAD_LETTERED
    }

    static DeliveryResult delivered(String group, ReceiverRegistration receiver) {
        return new DeliveryResult(Status.DELIVERED, group, List.of(receiver.name()), List.of());
    }

    static DeliveryResult deliveredToEveryMember(String group, List<String> receivers) {
        return new DeliveryResult(Status.DELIVERED, group, List.copyOf(receivers), List.of());
    }

    static DeliveryResult partiallyDelivered(String group, List<String> receivers, List<String> failures) {
        return new DeliveryResult(
                Status.PARTIALLY_DELIVERED, group, List.copyOf(receivers), List.copyOf(failures));
    }

    static DeliveryResult noReceiver(String group) {
        return new DeliveryResult(Status.NO_RECEIVER, group, List.of(), List.of());
    }

    static DeliveryResult deadLettered(String group, List<String> failures) {
        return new DeliveryResult(Status.DEAD_LETTERED, group, List.of(), List.copyOf(failures));
    }
}
