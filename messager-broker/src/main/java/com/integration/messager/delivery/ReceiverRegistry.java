package com.integration.messager.delivery;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
public class ReceiverRegistry {

    private final Map<String, ReceiverRegistration> byId = new ConcurrentHashMap<>();
    private final Map<String, ReceiverRegistration> byName = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<ReceiverRegistration>> byGroup = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> cursors = new ConcurrentHashMap<>();

    private final Object registrationLock = new Object();

    public ReceiverRegistration register(String name, String group, String callbackUrl) {
        synchronized (registrationLock) {
            ReceiverRegistration existing = byName.get(name);
            if (existing != null) {
                if (existing.group().equals(group) && existing.callbackUrl().equals(callbackUrl)) {
                    log.info("Receiver '{}' re-registered unchanged", name);
                    return existing;
                }
                log.info("Receiver '{}' re-registered with new details ({} in '{}' -> {} in '{}')",
                        name, existing.callbackUrl(), existing.group(), callbackUrl, group);
                removeFromIndexes(existing);
            }

            ReceiverRegistration registration =
                    new ReceiverRegistration(UUID.randomUUID().toString(), name, group, callbackUrl);

            byId.put(registration.id(), registration);
            byName.put(name, registration);
            byGroup.computeIfAbsent(group, g -> new CopyOnWriteArrayList<>()).add(registration);

            log.info("Receiver '{}' joined group '{}' at {}", name, group, callbackUrl);
            return registration;
        }
    }

    public Optional<ReceiverRegistration> findByName(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public Optional<ReceiverRegistration> findById(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** Every address a sender can write as {@code @name}, sorted. */
    public List<String> names() {
        return List.copyOf(new TreeSet<>(byName.keySet()));
    }

    /** Accepts either the receiver's id or its name. */
    public boolean unregister(String idOrName) {
        synchronized (registrationLock) {
            ReceiverRegistration target = byId.get(idOrName);
            if (target == null) {
                target = byName.get(idOrName);
            }
            if (target == null) {
                return false;
            }
            removeFromIndexes(target);
            log.info("Receiver '{}' left group '{}'", target.name(), target.group());
            return true;
        }
    }

    /** Drops every receiver of a group; used when the group itself is removed. */
    public int unregisterGroup(String group) {
        synchronized (registrationLock) {
            CopyOnWriteArrayList<ReceiverRegistration> receivers = byGroup.remove(group);
            if (receivers == null) {
                return 0;
            }
            receivers.forEach(r -> {
                byId.remove(r.id());
                byName.remove(r.name());
            });
            cursors.remove(group);
            return receivers.size();
        }
    }

    public List<ReceiverRegistration> all() {
        return List.copyOf(byId.values());
    }

    public List<ReceiverRegistration> forGroup(String group) {
        CopyOnWriteArrayList<ReceiverRegistration> receivers = byGroup.get(group);
        return receivers == null ? List.of() : List.copyOf(receivers);
    }

    public List<ReceiverRegistration> candidatesFor(String group) {
        List<ReceiverRegistration> receivers = forGroup(group);
        if (receivers.size() < 2) {
            return receivers;
        }

        int start = Math.floorMod(
                cursors.computeIfAbsent(group, g -> new AtomicInteger()).getAndIncrement(),
                receivers.size());

        List<ReceiverRegistration> rotated = new ArrayList<>(receivers.size());
        for (int i = 0; i < receivers.size(); i++) {
            rotated.add(receivers.get((start + i) % receivers.size()));
        }
        return rotated;
    }

    private void removeFromIndexes(ReceiverRegistration registration) {
        byId.remove(registration.id());
        byName.remove(registration.name());
        CopyOnWriteArrayList<ReceiverRegistration> receivers = byGroup.get(registration.group());
        if (receivers != null) {
            receivers.remove(registration);
        }
    }
}
