package com.integration.messager.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage implements Serializable {

    @NotBlank
    private String id;

    @NotBlank
    @Size(max = 64)
    private String sender;

    @NotBlank
    @Size(max = 4096)
    private String content;

    @NotNull
    private Instant sentAt;
}
