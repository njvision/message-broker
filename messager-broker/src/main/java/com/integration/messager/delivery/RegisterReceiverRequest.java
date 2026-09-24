package com.integration.messager.delivery;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** What a receiver application sends to join a group. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RegisterReceiverRequest {

    @NotBlank
    @Pattern(regexp = "[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}",
            message = "must be a plain name: letters, digits, '.', '_' or '-'")
    private String name;

    @NotBlank
    @Pattern(regexp = "[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}",
            message = "must be a plain name: letters, digits, '_' or '-'")
    private String group;

    @NotBlank
    @Pattern(regexp = "https?://.+", message = "must be an http(s) URL the broker can reach")
    @Size(max = 512)
    private String callbackUrl;
}
