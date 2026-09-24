package com.integration.messager.store;

import com.integration.messager.model.ChatMessage;

import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class BoundedMessageChannel {

    private final String name;
    private final int capacity;

    private final Deque<ChatMessage> messages = new ConcurrentLinkedDeque<>();
    private final AtomicInteger size = new AtomicInteger();
    private final AtomicLong totalReceived = new AtomicLong();
    private final AtomicLong totalEvicted = new AtomicLong();

    public BoundedMessageChannel(String name, int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("A channel needs room for at least one message");
        }
        this.name = name;
        this.capacity = capacity;
    }

    public void add(ChatMessage message) {
        messages.addFirst(message);
        totalReceived.incrementAndGet();

        int current = size.incrementAndGet();
        while (current > capacity) {
            if (messages.pollLast() == null) {
                break;
            }
            totalEvicted.incrementAndGet();
            current = size.decrementAndGet();
        }
    }

    public List<ChatMessage> snapshot() {
        return List.copyOf(messages);
    }

    public String name() {
        return name;
    }

    public Stats stats() {
        return new Stats(name, size.get(), capacity, totalReceived.get(), totalEvicted.get());
    }

    public record Stats(String channel, int buffered, int capacity, long received, long evicted) {
    }
}
