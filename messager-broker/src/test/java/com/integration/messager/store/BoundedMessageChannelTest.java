package com.integration.messager.store;

import com.integration.messager.model.ChatMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The transient channel is written to by every consumer thread at once, so the
 * bound has to hold under contention - not just in a single-threaded test.
 */
class BoundedMessageChannelTest {

    private static ChatMessage message(int n) {
        return ChatMessage.builder()
                .id("m-" + n)
                .sender("test")
                .content("payload " + n)
                .sentAt(Instant.now())
                .build();
    }

    @Test
    void keepsTheNewestMessagesAndDropsTheRest() {
        BoundedMessageChannel channel = new BoundedMessageChannel("test", 3);

        for (int i = 1; i <= 5; i++) {
            channel.add(message(i));
        }

        assertThat(channel.snapshot()).extracting(ChatMessage::getId)
                .containsExactly("m-5", "m-4", "m-3");
        assertThat(channel.stats().received()).isEqualTo(5);
        assertThat(channel.stats().evicted()).isEqualTo(2);
    }

    @Test
    void staysWithinCapacityWhenManyThreadsWriteAtOnce() throws Exception {
        int threads = 8;
        int perThread = 500;
        int capacity = 100;

        BoundedMessageChannel channel = new BoundedMessageChannel("test", capacity);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            channel.add(message(i));
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        }

        List<ChatMessage> snapshot = channel.snapshot();
        assertThat(snapshot).hasSize(capacity);
        assertThat(channel.stats().buffered()).isEqualTo(capacity);
        assertThat(channel.stats().received()).isEqualTo((long) threads * perThread);
        assertThat(channel.stats().evicted()).isEqualTo((long) threads * perThread - capacity);
    }
}
