package com.smartfirehub.pipeline.controller;

import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.pipeline.dto.*;
import com.smartfirehub.pipeline.service.PipelineService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/pipelines")
public class PipelineController {

    private final PipelineService pipelineService;

    public PipelineController(PipelineService pipelineService) {
        this.pipelineService = pipelineService;
    }

    @GetMapping
    @RequirePermission("pipeline:read")
    public ResponseEntity<PageResponse<PipelineResponse>> getPipelines(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageResponse<PipelineResponse> response = pipelineService.getPipelines(page, size);
        return ResponseEntity.ok(response);
    }

    @PostMapping
    @RequirePermission("pipeline:write")
    public ResponseEntity<PipelineDetailResponse> createPipeline(@RequestBody CreatePipelineRequest request) {
        Long userId = Long.parseLong(SecurityContextHolder.getContext().getAuthentication().getName());
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
    public ResponseEntity<Void> updatePipeline(@PathVariable Long id, @RequestBody UpdatePipelineRequest request) {
        pipelineService.updatePipeline(id, request);
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
    public ResponseEntity<PipelineExecutionResponse> executePipeline(@PathVariable Long id) {
        Long userId = Long.parseLong(SecurityContextHolder.getContext().getAuthentication().getName());
        PipelineExecutionResponse execution = pipelineService.executePipeline(id, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(execution);
    }

    @GetMapping("/{id}/executions")
    @RequirePermission("pipeline:read")
    public ResponseEntity<List<PipelineExecutionResponse>> getExecutionsByPipelineId(@PathVariable Long id) {
        List<PipelineExecutionResponse> executions = pipelineService.getExecutionsByPipelineId(id);
        return ResponseEntity.ok(executions);
    }

    @GetMapping("/{id}/executions/{execId}")
    @RequirePermission("pipeline:read")
    public ResponseEntity<ExecutionDetailResponse> getExecutionById(
            @PathVariable Long id,
            @PathVariable Long execId) {
        ExecutionDetailResponse execution = pipelineService.getExecutionById(id, execId);
        return ResponseEntity.ok(execution);
    }
}
