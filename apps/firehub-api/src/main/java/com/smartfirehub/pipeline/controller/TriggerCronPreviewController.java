package com.smartfirehub.pipeline.controller;

import com.smartfirehub.global.dto.CronPreviewResponse;
import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.global.util.CronPreview;
import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 스케줄 트리거 편집 창의 「다음 5회 실행 예정」 미리보기 (WD-391).
 *
 * <p>스케줄러와 같은 서머타임 규칙({@link com.smartfirehub.global.util.DstAwareCron})으로 서버가 계산해, 화면 미리보기와 실제 실행이
 * 전환일에도 같다. 파이프라인과 무관한 계산이라 파이프라인 경로가 아닌 {@code /api/v1/triggers} 아래에 두고, 트리거 조회 권한으로 연다.
 */
@RestController
public class TriggerCronPreviewController {

  @GetMapping("/api/v1/triggers/cron-preview")
  @RequirePermission("trigger:read")
  public ResponseEntity<CronPreviewResponse> preview(
      @RequestParam String cron,
      @RequestParam(required = false) String timezone,
      @RequestParam(defaultValue = "" + CronPreview.DEFAULT_COUNT) int count) {
    return ResponseEntity.ok(CronPreview.preview(cron, timezone, count, Instant.now()));
  }
}
