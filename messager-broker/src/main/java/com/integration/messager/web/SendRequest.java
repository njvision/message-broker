package com.integration.messager.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SendRequest {

    @NotBlank(message = "must be given, so the receiver knows who sent this")
    @Size(max = 64)
    private String sender;

    @NotBlank(message = "an empty message is not worth routing")
    @Size(max = 4096)
    private String content;

    @Size(max = 64)
    private String target;
}
