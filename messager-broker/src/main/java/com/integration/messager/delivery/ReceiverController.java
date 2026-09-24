package com.integration.messager.delivery;

import com.integration.messager.admin.GroupAdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/receivers")
@RequiredArgsConstructor
public class ReceiverController {

    private final ReceiverRegistry registry;
    private final GroupAdminService groupAdmin;

    @GetMapping
    public List<Map<String, Object>> list() {
        return registry.all().stream()
                .sorted(Comparator.comparing(ReceiverRegistration::group)
                        .thenComparing(ReceiverRegistration::name))
                .map(ReceiverRegistration::describe)
                .toList();
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> register(@Valid @RequestBody RegisterReceiverRequest request) {
        try {
            groupAdmin.ensureGroup(request.getGroup());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }

        ReceiverRegistration registration = registry.register(
                request.getName(), request.getGroup(), request.getCallbackUrl());

        return ResponseEntity.status(HttpStatus.CREATED).body(registration.describe());
    }

    @DeleteMapping("/{idOrName}")
    public Map<String, Object> unregister(@PathVariable String idOrName) {
        if (!registry.unregister(idOrName)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown receiver '" + idOrName + "'");
        }
        return Map.of("receiver", idOrName, "unregistered", true);
    }
}
