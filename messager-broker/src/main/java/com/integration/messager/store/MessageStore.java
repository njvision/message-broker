package com.integration.messager.store;

import com.integration.messager.config.MessagerProperties;
import com.integration.messager.model.ChatMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class MessageStore {

    private final MessagerProperties properties;

    private final Map<String, BoundedMessageChannel> channels = new ConcurrentHashMap<>();

    public void record(String channel, ChatMessage message) {
        channelFor(channel).add(message);
    }

    public List<ChatMessage> snapshot(String channel) {
        BoundedMessageChannel existing = channels.get(channel);
        return existing == null ? List.of() : existing.snapshot();
    }

    /** Every channel the broker has seen traffic on, keyed by queue name. */
    public Map<String, List<ChatMessage>> snapshotAll() {
        Map<String, List<ChatMessage>> all = new LinkedHashMap<>();
        new TreeMap<>(channels).forEach((name, channel) -> all.put(name, channel.snapshot()));
        return all;
    }

    public List<BoundedMessageChannel.Stats> stats() {
        return new TreeMap<>(channels).values().stream()
                .map(BoundedMessageChannel::stats)
                .toList();
    }

    private BoundedMessageChannel channelFor(String channel) {
        return channels.computeIfAbsent(channel,
                name -> new BoundedMessageChannel(name, properties.historyLimit()));
    }
}
