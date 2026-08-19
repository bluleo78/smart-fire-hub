package com.smartfirehub.platform.controller;

import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.platform.dto.CreateTenantRequest;
import com.smartfirehub.platform.dto.TenantMemberResponse;
import com.smartfirehub.platform.dto.TenantSummaryResponse;
import com.smartfirehub.platform.service.PlatformTenantService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 테넌트 생명주기 API(운영자 전용).
 *
 * <p>권한은 V82 가 시딩한 {@code platform:*} 코드로 검사한다. 이 코드들은 플랫폼 평면 로딩 경로
 * ({@code platform_user_role ⨝ platform_role_permission})로만 authority 에 실리며,
 * {@code PlatformPlaneFilter} 가 테넌트 토큰의 접근을 평면 단위로 먼저 차단한다.
 *
 * <p>정지·활성화가 같은 권한({@code platform:tenant:suspend})을 쓰는 이유: 둘은 같은 상태 스위치의
 * 양방향이고, 정지시킬 수 있는 운영자가 되돌릴 수 없으면 실수를 복구할 수 없다.
 */
@RestController
@RequestMapping("/api/platform/tenants")
@RequiredArgsConstructor
public class PlatformTenantController {

  private final PlatformTenantService tenantService;

  @GetMapping
  @RequirePermission("platform:tenant:read")
  public ResponseEntity<List<TenantSummaryResponse>> list() {
    return ResponseEntity.ok(tenantService.list());
  }

  @GetMapping("/{tenantId}")
  @RequirePermission("platform:tenant:read")
  public ResponseEntity<TenantSummaryResponse> detail(@PathVariable long tenantId) {
    return ResponseEntity.ok(tenantService.detail(tenantId));
  }

  /** 테넌트 생성. 초기 Owner 지정과 기본 시드가 한 트랜잭션에서 일어난다. */
  @PostMapping
  @RequirePermission("platform:tenant:create")
  public ResponseEntity<TenantSummaryResponse> create(
      @Valid @RequestBody CreateTenantRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(tenantService.create(request));
  }

  @PostMapping("/{tenantId}/suspend")
  @RequirePermission("platform:tenant:suspend")
  public ResponseEntity<Void> suspend(@PathVariable long tenantId) {
    tenantService.suspend(tenantId);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{tenantId}/activate")
  @RequirePermission("platform:tenant:suspend")
  public ResponseEntity<Void> activate(@PathVariable long tenantId) {
    tenantService.activate(tenantId);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/{tenantId}/members")
  @RequirePermission("platform:member:read")
  public ResponseEntity<List<TenantMemberResponse>> members(@PathVariable long tenantId) {
    return ResponseEntity.ok(tenantService.members(tenantId));
  }
}
