package com.integration.simulator;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {

    private String id;
    private String sender;
    private String content;
    private Instant sentAt;
}
