package com.smartfirehub.pipeline.controller;

import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.pipeline.dto.*;
import com.smartfirehub.pipeline.service.TriggerService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/pipelines/{pipelineId}/triggers")
public class TriggerController {

    private final TriggerService triggerService;

    public TriggerController(TriggerService triggerService) {
        this.triggerService = triggerService;
    }

    @GetMapping
    @RequirePermission("trigger:read")
    public ResponseEntity<List<TriggerResponse>> getTriggers(@PathVariable Long pipelineId) {
        List<TriggerResponse> triggers = triggerService.getTriggers(pipelineId);
        return ResponseEntity.ok(triggers);
    }

    @PostMapping
    @RequirePermission("trigger:write")
    public ResponseEntity<TriggerResponse> createTrigger(
            @PathVariable Long pipelineId,
            @RequestBody CreateTriggerRequest request) {
        Long userId = Long.parseLong(SecurityContextHolder.getContext().getAuthentication().getName());
        TriggerResponse trigger = triggerService.createTrigger(pipelineId, request, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(trigger);
    }

    @GetMapping("/{triggerId}")
    @RequirePermission("trigger:read")
    public ResponseEntity<TriggerResponse> getTriggerById(
            @PathVariable Long pipelineId,
            @PathVariable Long triggerId) {
        TriggerResponse trigger = triggerService.getTriggerById(triggerId);
        return ResponseEntity.ok(trigger);
    }

    @PutMapping("/{triggerId}")
    @RequirePermission("trigger:write")
    public ResponseEntity<Void> updateTrigger(
            @PathVariable Long pipelineId,
            @PathVariable Long triggerId,
            @RequestBody UpdateTriggerRequest request) {
        Long userId = Long.parseLong(SecurityContextHolder.getContext().getAuthentication().getName());
        triggerService.updateTrigger(triggerId, request, userId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{triggerId}")
    @RequirePermission("trigger:delete")
    public ResponseEntity<Void> deleteTrigger(
            @PathVariable Long pipelineId,
            @PathVariable Long triggerId) {
        triggerService.deleteTrigger(triggerId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{triggerId}/toggle")
    @RequirePermission("trigger:write")
    public ResponseEntity<Void> toggleTrigger(
            @PathVariable Long pipelineId,
            @PathVariable Long triggerId) {
        TriggerResponse existing = triggerService.getTriggerById(triggerId);
        triggerService.toggleTrigger(triggerId, !existing.isEnabled());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/events")
    @RequirePermission("trigger:read")
    public ResponseEntity<List<TriggerEventResponse>> getTriggerEvents(
            @PathVariable Long pipelineId,
            @RequestParam(defaultValue = "20") int limit) {
        List<TriggerEventResponse> events = triggerService.getTriggerEvents(pipelineId, limit);
        return ResponseEntity.ok(events);
    }
}
