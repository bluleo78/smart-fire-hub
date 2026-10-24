package com.smartfirehub.pipeline.controller;

import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.pipeline.dto.*;
import com.smartfirehub.pipeline.service.TriggerService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/pipelines/{pipelineId}/triggers")
@RequiredArgsConstructor
public class TriggerController {

  private final TriggerService triggerService;

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
      @Valid @RequestBody CreateTriggerRequest request,
      Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    TriggerResponse trigger = triggerService.createTrigger(pipelineId, request, userId);
    return ResponseEntity.status(HttpStatus.CREATED).body(trigger);
  }

  @GetMapping("/{triggerId}")
  @RequirePermission("trigger:read")
  public ResponseEntity<TriggerResponse> getTriggerById(
      @PathVariable Long pipelineId, @PathVariable Long triggerId) {
    TriggerResponse trigger = triggerService.getTriggerById(pipelineId, triggerId);
    return ResponseEntity.ok(trigger);
  }

  @PutMapping("/{triggerId}")
  @RequirePermission("trigger:write")
  public ResponseEntity<Void> updateTrigger(
      @PathVariable Long pipelineId,
      @PathVariable Long triggerId,
      @Valid @RequestBody UpdateTriggerRequest request,
      Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    triggerService.updateTrigger(pipelineId, triggerId, request, userId);
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/{triggerId}")
  @RequirePermission("trigger:delete")
  public ResponseEntity<Void> deleteTrigger(
      @PathVariable Long pipelineId, @PathVariable Long triggerId) {
    triggerService.deleteTrigger(pipelineId, triggerId);
    return ResponseEntity.noContent().build();
  }

  @PatchMapping("/{triggerId}/toggle")
  @RequirePermission("trigger:write")
  public ResponseEntity<Void> toggleTrigger(
      @PathVariable Long pipelineId, @PathVariable Long triggerId) {
    // 경로의 파이프라인 소속인 트리거만 다룬다 — 다른 파이프라인의 트리거는 없는 트리거와 같은 404(WD-228).
    TriggerResponse existing = triggerService.getTriggerById(pipelineId, triggerId);
    triggerService.toggleTrigger(pipelineId, triggerId, !existing.isEnabled());
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/events")
  @RequirePermission("trigger:read")
  public ResponseEntity<List<TriggerEventResponse>> getTriggerEvents(
      @PathVariable Long pipelineId, @RequestParam(defaultValue = "20") int limit) {
    List<TriggerEventResponse> events = triggerService.getTriggerEvents(pipelineId, limit);
    return ResponseEntity.ok(events);
  }
}
