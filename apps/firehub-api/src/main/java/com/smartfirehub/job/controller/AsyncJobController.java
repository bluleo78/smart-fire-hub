package com.smartfirehub.job.controller;

import com.smartfirehub.job.dto.AsyncJobStatusResponse;
import com.smartfirehub.job.service.AsyncJobService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/jobs")
@RequiredArgsConstructor
public class AsyncJobController {

  private final AsyncJobService asyncJobService;

  /**
   * 작업 진행 SSE 구독. 기능 권한(@RequirePermission)을 두지 않고 인증 + 작업 소유자 확인만 한다(WD-203) — 작업은 data:import·
   * apiconnection:write·data:export 등 서로 다른 권한으로 시작되므로, data:read 하나로 막으면 권한 있는 작업을 시작한 사람이 자기 작업을
   * 지켜보지 못해 화면이 「갱신 중...」에 영원히 멈췄다. 접근 경계는 {@link AsyncJobService} 의 소유자 확인과 async_job RLS 다.
   */
  @GetMapping(value = "/{jobId}/progress", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter streamProgress(@PathVariable String jobId, Authentication auth) {
    Long userId = (Long) auth.getPrincipal();
    return asyncJobService.subscribe(jobId, userId);
  }

  /** 작업 상태 조회(REST 폴백). 진행 SSE 와 같은 이유로 기능 권한 없이 인증 + 소유자 확인만 한다(WD-203). */
  @GetMapping("/{jobId}/status")
  public AsyncJobStatusResponse getJobStatus(@PathVariable String jobId, Authentication auth) {
    Long userId = (Long) auth.getPrincipal();
    return asyncJobService.getJobStatus(jobId, userId);
  }
}
