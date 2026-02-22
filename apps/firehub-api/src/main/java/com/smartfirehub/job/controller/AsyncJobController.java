package com.smartfirehub.job.controller;

import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.job.dto.AsyncJobStatusResponse;
import com.smartfirehub.job.service.AsyncJobService;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/jobs")
public class AsyncJobController {

  private final AsyncJobService asyncJobService;

  public AsyncJobController(AsyncJobService asyncJobService) {
    this.asyncJobService = asyncJobService;
  }

  @GetMapping(value = "/{jobId}/progress", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  @RequirePermission("data:read")
  public SseEmitter streamProgress(@PathVariable String jobId, Authentication auth) {
    Long userId = (Long) auth.getPrincipal();
    return asyncJobService.subscribe(jobId, userId);
  }

  @GetMapping("/{jobId}/status")
  @RequirePermission("data:read")
  public AsyncJobStatusResponse getJobStatus(@PathVariable String jobId, Authentication auth) {
    Long userId = (Long) auth.getPrincipal();
    return asyncJobService.getJobStatus(jobId, userId);
  }
}
