package com.integration.messager.admin;

import com.integration.messager.config.MessagerProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;

@RestController
@RequestMapping("/api/groups")
@RequiredArgsConstructor
public class GroupAdminController {

    private final GroupAdminService groupAdmin;
    private final MessagerProperties properties;

    @GetMapping
    public List<Map<String, Object>> list() {
        return new TreeSet<>(groupAdmin.groups()).stream()
                .map(group -> Map.<String, Object>of(
                        "group", group,
                        "queue", properties.queueFor(group),
                        "routingKey", properties.routingKeyFor(group),
                        "activeConsumers", groupAdmin.concurrencyOf(group)))
                .toList();
    }

    @PostMapping("/{group}")
    public ResponseEntity<Map<String, Object>> add(
            @PathVariable String group,
            @RequestParam(defaultValue = "0") int consumers) {

        int concurrency = consumers > 0 ? consumers : properties.concurrencyPerGroup();
        try {
            groupAdmin.addGroup(group, concurrency);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "group", group,
                "queue", properties.queueFor(group),
                "consumers", concurrency));
    }

    @DeleteMapping("/{group}")
    public Map<String, Object> remove(
            @PathVariable String group,
            @RequestParam(defaultValue = "false") boolean deleteQueue) {
        try {
            groupAdmin.removeGroup(group, deleteQueue);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        }
        return Map.of("group", group, "removed", true, "queueDeleted", deleteQueue);
    }

    @PutMapping("/{group}/consumers")
    public Map<String, Object> resize(@PathVariable String group, @RequestParam int consumers) {
        try {
            return Map.of("group", group, "consumers", groupAdmin.setConcurrency(group, consumers));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }
}
