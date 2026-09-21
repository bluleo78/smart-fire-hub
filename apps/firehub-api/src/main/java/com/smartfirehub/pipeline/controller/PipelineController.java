package com.smartfirehub.pipeline.controller;

import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.pipeline.dto.*;
import com.smartfirehub.pipeline.service.ApiCallPreviewService;
import com.smartfirehub.pipeline.service.PipelineService;
import com.smartfirehub.pipeline.service.TriggerService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/pipelines")
@RequiredArgsConstructor
@org.springframework.validation.annotation.Validated
public class PipelineController {

  private final PipelineService pipelineService;
  private final TriggerService triggerService;
  private final ApiCallPreviewService apiCallPreviewService;

  @GetMapping
  @RequirePermission("pipeline:read")
  public ResponseEntity<PageResponse<PipelineResponse>> getPipelines(
      @RequestParam(defaultValue = "0") @jakarta.validation.constraints.Min(0) int page,
      @RequestParam(defaultValue = "20")
          @jakarta.validation.constraints.Min(1)
          @jakarta.validation.constraints.Max(200)
          int size) {
    PageResponse<PipelineResponse> response = pipelineService.getPipelines(page, size);
    return ResponseEntity.ok(response);
  }

  @PostMapping
  @RequirePermission("pipeline:write")
  public ResponseEntity<PipelineDetailResponse> createPipeline(
      @Valid @RequestBody CreatePipelineRequest request, Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    PipelineDetailResponse pipeline = pipelineService.createPipeline(request, userId);
    return ResponseEntity.status(HttpStatus.CREATED).body(pipeline);
  }

  @GetMapping("/{id}")
  @RequirePermission("pipeline:read")
  public ResponseEntity<PipelineDetailResponse> getPipelineById(@PathVariable Long id) {
    PipelineDetailResponse pipeline = pipelineService.getPipelineById(id);
    return ResponseEntity.ok(pipeline);
  }

  @PutMapping("/{id}")
  @RequirePermission("pipeline:write")
  public ResponseEntity<Void> updatePipeline(
      @PathVariable Long id,
      @Valid @RequestBody UpdatePipelineRequest request,
      Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    pipelineService.updatePipeline(id, request, userId);
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/{id}")
  @RequirePermission("pipeline:delete")
  public ResponseEntity<Void> deletePipeline(@PathVariable Long id) {
    pipelineService.deletePipeline(id);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{id}/execute")
  @RequirePermission("pipeline:execute")
  public ResponseEntity<PipelineExecutionResponse> executePipeline(
      @PathVariable Long id, Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    PipelineExecutionResponse execution =
        pipelineService.executePipeline(id, userId, "MANUAL", null);
    return ResponseEntity.status(HttpStatus.CREATED).body(execution);
  }

  @GetMapping("/{id}/executions")
  @RequirePermission("pipeline:read")
  public ResponseEntity<List<PipelineExecutionResponse>> getExecutionsByPipelineId(
      @PathVariable Long id) {
    List<PipelineExecutionResponse> executions = pipelineService.getExecutionsByPipelineId(id);
    return ResponseEntity.ok(executions);
  }

  @GetMapping("/{id}/executions/{execId}")
  @RequirePermission("pipeline:read")
  public ResponseEntity<ExecutionDetailResponse> getExecutionById(
      @PathVariable Long id, @PathVariable Long execId) {
    ExecutionDetailResponse execution = pipelineService.getExecutionById(id, execId);
    return ResponseEntity.ok(execution);
  }

  @GetMapping("/{id}/trigger-events")
  @RequirePermission("trigger:read")
  public ResponseEntity<List<TriggerEventResponse>> getTriggerEvents(
      @PathVariable Long id, @RequestParam(defaultValue = "20") int limit) {
    List<TriggerEventResponse> events = triggerService.getTriggerEvents(id, limit);
    return ResponseEntity.ok(events);
  }

  /**
   * 스텝의 다음 실행에서 전체 재생성(SELECT 자동 적재 스텝)/전체 재읽기(사용자 DML 스텝)을 예약한다.
   * 스텝이 {@code {{last_run_at}}} 을 쓰지 않으면 예약할 수 없다(400) — 그런 스텝은 실행기가 증분 경로를
   * 타지 않아 플래그를 영원히 해제하지 못한다. 수정 권한(PUT `/{id}`)과 같은 권한을 요구한다.
   */
  @PostMapping("/{id}/steps/{stepId}/full-rebuild")
  @RequirePermission("pipeline:write")
  public ResponseEntity<Void> reserveFullRebuild(@PathVariable Long id, @PathVariable Long stepId) {
    pipelineService.setFullRebuildPending(id, stepId, true);
    return ResponseEntity.noContent().build();
  }

  /** 전체 재생성/재읽기 예약을 취소한다. 예약이 없어도 멱등하게 204를 반환한다. */
  @DeleteMapping("/{id}/steps/{stepId}/full-rebuild")
  @RequirePermission("pipeline:write")
  public ResponseEntity<Void> cancelFullRebuild(@PathVariable Long id, @PathVariable Long stepId) {
    pipelineService.setFullRebuildPending(id, stepId, false);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/api-call/preview")
  @RequirePermission("pipeline:write")
  public ResponseEntity<ApiCallPreviewResponse> previewApiCall(
      @RequestBody ApiCallPreviewRequest request) {
    ApiCallPreviewResponse response = apiCallPreviewService.preview(request);
    return ResponseEntity.ok(response);
  }
}
