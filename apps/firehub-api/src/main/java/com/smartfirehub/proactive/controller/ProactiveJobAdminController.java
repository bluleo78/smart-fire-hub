package com.smartfirehub.proactive.controller;

import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.proactive.dto.ProactiveJobAdminSummaryResponse;
import com.smartfirehub.proactive.service.ProactiveJobAdminService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 워크스페이스 스마트 작업 관리 API — proactive:admin 전용(WD-192).
 *
 * <p>목록(최소 정보)·끄기·삭제만 있다. 남의 작업 상세·편집·켜기·지금 실행은 의도적으로 두지 않는다(작성자 데이터 권한으로 만든 내용 보호, 자동 실행 증가 방지).
 * 작성자 경로({@code /api/v1/proactive/jobs/{id}})와 섞이지 않게 별도 접두사를 쓴다 — {@code /{id}} 매핑과 충돌하지 않는다.
 */
@RestController
@RequestMapping("/api/v1/proactive/admin/jobs")
@RequiredArgsConstructor
public class ProactiveJobAdminController {

  private final ProactiveJobAdminService adminService;

  /** 이 워크스페이스의 모든 스마트 작업(이름·작성자·활성·실행 자격·다음 실행·마지막 실행 상태). */
  @GetMapping
  @RequirePermission("proactive:admin")
  public ResponseEntity<List<ProactiveJobAdminSummaryResponse>> getWorkspaceJobs() {
    return ResponseEntity.ok(adminService.getWorkspaceJobs());
  }

  /** 남의 작업이라도 끈다(켜기는 없다). 다른 워크스페이스·없는 작업은 404. */
  @PostMapping("/{id}/disable")
  @RequirePermission("proactive:admin")
  public ResponseEntity<Void> disableJob(@PathVariable Long id, Authentication authentication) {
    adminService.disableJob(id, (Long) authentication.getPrincipal());
    return ResponseEntity.noContent().build();
  }

  /** 남의 작업이라도 삭제한다. 다른 워크스페이스·없는 작업은 404. */
  @DeleteMapping("/{id}")
  @RequirePermission("proactive:admin")
  public ResponseEntity<Void> deleteJob(@PathVariable Long id, Authentication authentication) {
    adminService.deleteJob(id, (Long) authentication.getPrincipal());
    return ResponseEntity.noContent().build();
  }
}
