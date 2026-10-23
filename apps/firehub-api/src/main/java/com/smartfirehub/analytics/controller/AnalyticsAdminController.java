package com.smartfirehub.analytics.controller;

import com.smartfirehub.analytics.dto.AnalyticsAdminItemResponse;
import com.smartfirehub.analytics.dto.DeleteImpactResponse;
import com.smartfirehub.analytics.service.AnalyticsAdminService;
import com.smartfirehub.analytics.service.AnalyticsAssetKind;
import com.smartfirehub.global.security.RequirePermission;
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
 * 워크스페이스 분석 자산 관리 API — analytics:admin 전용(WD-199).
 *
 * <p>대시보드·차트·저장 쿼리 각각에 목록(최소 정보)·공유 해제·삭제(+차트·쿼리 삭제 영향)만 있다. 남의 자산 내용 열람·편집·실행은 의도적으로 두지 않는다(작성자
 * 데이터 권한으로 만든 내용 보호). 작성자 경로({@code /api/v1/analytics/{type}/{id}})와 섞이지 않게 별도 접두사를 쓴다. 종류 경로는
 * 정규식으로 제한해 그 밖의 값은 매핑되지 않는다(404).
 */
@RestController
@RequestMapping("/api/v1/analytics/admin")
@RequiredArgsConstructor
public class AnalyticsAdminController {

  private final AnalyticsAdminService adminService;

  /** 이 워크스페이스의 해당 종류 자산 전부(이름·작성자·작성자 활성 여부·공유 여부·수정일). */
  @GetMapping("/{type:dashboards|charts|queries}")
  @RequirePermission("analytics:admin")
  public ResponseEntity<List<AnalyticsAdminItemResponse>> getWorkspaceItems(
      @PathVariable String type) {
    return ResponseEntity.ok(adminService.getWorkspaceItems(AnalyticsAssetKind.fromPath(type)));
  }

  /** 남의 자산이라도 공유를 해제한다(공유하기는 없다). 다른 워크스페이스·없는 자산은 404. */
  @PostMapping("/{type:dashboards|charts|queries}/{id}/unshare")
  @RequirePermission("analytics:admin")
  public ResponseEntity<Void> unshare(
      @PathVariable String type, @PathVariable Long id, Authentication authentication) {
    adminService.unshare(
        AnalyticsAssetKind.fromPath(type), id, (Long) authentication.getPrincipal());
    return ResponseEntity.noContent().build();
  }

  /** 남의 자산이라도 삭제한다(작성자 삭제와 같은 CASCADE). 다른 워크스페이스·없는 자산은 404. */
  @DeleteMapping("/{type:dashboards|charts|queries}/{id}")
  @RequirePermission("analytics:admin")
  public ResponseEntity<Void> delete(
      @PathVariable String type, @PathVariable Long id, Authentication authentication) {
    adminService.delete(
        AnalyticsAssetKind.fromPath(type), id, (Long) authentication.getPrincipal());
    return ResponseEntity.noContent().build();
  }

  /** 차트·쿼리 삭제 시 함께 사라지는 항목 — 이름은 관리자에게 보이는 것만, 볼 수 없는 것은 개수만. */
  @GetMapping("/{type:charts|queries}/{id}/delete-impact")
  @RequirePermission("analytics:admin")
  public ResponseEntity<DeleteImpactResponse> getDeleteImpact(
      @PathVariable String type, @PathVariable Long id, Authentication authentication) {
    return ResponseEntity.ok(
        adminService.getDeleteImpact(
            AnalyticsAssetKind.fromPath(type), id, (Long) authentication.getPrincipal()));
  }
}
