package com.integration.messager.delivery;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public final class ReceiverRegistration {

    private final String id;
    private final String name;
    private final String group;
    private final String callbackUrl;
    private final Instant registeredAt = Instant.now();

    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong addressedDirectly = new AtomicLong();

    private volatile String lastError;
    private volatile Instant lastDeliveryAt;

    ReceiverRegistration(String id, String name, String group, String callbackUrl) {
        this.id = id;
        this.name = name;
        this.group = group;
        this.callbackUrl = callbackUrl;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String group() {
        return group;
    }

    public String callbackUrl() {
        return callbackUrl;
    }

    void recordSuccess(boolean direct) {
        delivered.incrementAndGet();
        if (direct) {
            addressedDirectly.incrementAndGet();
        }
        lastDeliveryAt = Instant.now();
        lastError = null;
    }

    void recordFailure(String error) {
        failed.incrementAndGet();
        lastError = error;
    }

    /** Ordered so the JSON reads the same way every time. */
    public Map<String, Object> describe() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", id);
        view.put("name", name);
        view.put("group", group);
        view.put("callbackUrl", callbackUrl);
        view.put("addressAs", "@" + name);
        view.put("registeredAt", registeredAt);
        view.put("delivered", delivered.get());
        view.put("addressedDirectly", addressedDirectly.get());
        view.put("failed", failed.get());
        view.put("lastDeliveryAt", lastDeliveryAt);
        view.put("lastError", lastError);
        return view;
    }
}
