package com.smartfirehub.proactive.controller;

import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.proactive.dto.CreateReportTemplateRequest;
import com.smartfirehub.proactive.dto.ReportTemplateResponse;
import com.smartfirehub.proactive.dto.ReportTemplateSummaryResponse;
import com.smartfirehub.proactive.dto.UpdateReportTemplateRequest;
import com.smartfirehub.proactive.service.ReportTemplateService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/proactive/templates")
@RequiredArgsConstructor
public class ReportTemplateController {

  private final ReportTemplateService reportTemplateService;

  // 목록 조회는 요약 응답(섹션 전체 JSONB 제외) + page/size 파라미터를 지원한다(#632).
  // 응답은 기존과 동일하게 바로 배열이다(엔벨로프로 감싸지 않음) — firehub-web의 여러 화면과
  // e2e 목이 배열 계약에 의존하고 있어(#547과 별개 UI 페이지네이션은 아직 없음) 엔벨로프 도입은
  // 불필요하게 넓은 프론트 변경을 요구한다. 기본 size=50은 현재 목록(9개)보다 넉넉해
  // 하위호환으로 계속 "전체 목록"처럼 동작한다.
  @GetMapping
  @RequirePermission("proactive:read")
  public ResponseEntity<List<ReportTemplateSummaryResponse>> getTemplates(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size,
      Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    page = Math.max(0, page);
    size = Math.max(1, Math.min(size, 200));
    return ResponseEntity.ok(reportTemplateService.getTemplates(userId, page, size));
  }

  @GetMapping("/{id}")
  @RequirePermission("proactive:read")
  public ResponseEntity<ReportTemplateResponse> getTemplate(@PathVariable Long id) {
    return ResponseEntity.ok(reportTemplateService.getTemplate(id));
  }

  @PostMapping
  @RequirePermission("proactive:write")
  public ResponseEntity<ReportTemplateResponse> createTemplate(
      @Valid @RequestBody CreateReportTemplateRequest request, Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    ReportTemplateResponse response = reportTemplateService.createTemplate(request, userId);
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @PutMapping("/{id}")
  @RequirePermission("proactive:write")
  public ResponseEntity<Void> updateTemplate(
      @PathVariable Long id,
      @Valid @RequestBody UpdateReportTemplateRequest request,
      Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    reportTemplateService.updateTemplate(id, request, userId);
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/{id}")
  @RequirePermission("proactive:write")
  public ResponseEntity<Void> deleteTemplate(@PathVariable Long id, Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    reportTemplateService.deleteTemplate(id, userId);
    return ResponseEntity.noContent().build();
  }
}
