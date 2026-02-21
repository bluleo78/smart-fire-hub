package com.smartfirehub.pipeline.event;

public record PipelineCompletedEvent(Long pipelineId, Long executionId, String status) {}
