package com.smartfirehub.platform.controller;

import com.smartfirehub.dataset.storage.external.ConnectionTestResult;
import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.platform.dto.CreateTenantRequest;
import com.smartfirehub.platform.dto.DataStoreBackendOption;
import com.smartfirehub.platform.dto.DataStoreConnectionTestRequest;
import com.smartfirehub.platform.dto.DataStoreScriptResponse;
import com.smartfirehub.platform.dto.DataStoreVerifyResponse;
import com.smartfirehub.platform.dto.TenantCreatedResponse;
import com.smartfirehub.platform.dto.TenantDataStoreResponse;
import com.smartfirehub.platform.dto.TenantMemberResponse;
import com.smartfirehub.platform.dto.TenantSummaryResponse;
import com.smartfirehub.platform.service.PlatformTenantService;
import com.smartfirehub.platform.service.TenantDataStoreSetupService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 테넌트 생명주기 API(운영자 전용).
 *
 * <p>권한은 V82 가 시딩한 {@code platform:*} 코드로 검사한다. 이 코드들은 플랫폼 평면 로딩 경로 ({@code platform_user_role ⨝
 * platform_role_permission})로만 authority 에 실리며, {@code PlatformPlaneFilter} 가 테넌트 토큰의 접근을 평면 단위로 먼저
 * 차단한다.
 *
 * <p>정지·활성화가 같은 권한({@code platform:tenant:suspend})을 쓰는 이유: 둘은 같은 상태 스위치의 양방향이고, 정지시킬 수 있는 운영자가 되돌릴
 * 수 없으면 실수를 복구할 수 없다.
 */
@RestController
@RequestMapping("/api/platform/tenants")
@RequiredArgsConstructor
public class PlatformTenantController {

  private final PlatformTenantService tenantService;
  private final TenantDataStoreSetupService dataStoreService;

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
  public ResponseEntity<TenantCreatedResponse> create(
      @Valid @RequestBody CreateTenantRequest request, Authentication authentication) {
    // 운영자 id 는 감사 행위자로 쓰인다(WD-12) — 계정 조치 컨트롤러와 같은 방식으로 principal 에서 꺼낸다.
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(tenantService.create(request, (Long) authentication.getPrincipal()));
  }

  @PostMapping("/{tenantId}/suspend")
  @RequirePermission("platform:tenant:suspend")
  public ResponseEntity<Void> suspend(@PathVariable long tenantId, Authentication authentication) {
    tenantService.suspend(tenantId, (Long) authentication.getPrincipal());
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{tenantId}/activate")
  @RequirePermission("platform:tenant:suspend")
  public ResponseEntity<Void> activate(@PathVariable long tenantId, Authentication authentication) {
    tenantService.activate(tenantId, (Long) authentication.getPrincipal());
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/{tenantId}/members")
  @RequirePermission("platform:member:read")
  public ResponseEntity<List<TenantMemberResponse>> members(@PathVariable long tenantId) {
    return ResponseEntity.ok(tenantService.members(tenantId));
  }

  // ── 데이터셋 저장소(WD-73) ────────────────────────────────────────────────
  // 리터럴 경로(/data-store/...)는 /{tenantId} 와 겹치지 않는다 — Spring 은 리터럴 매핑을 우선하고,
  // {tenantId} 는 long 이라 "data-store" 가 와도 매칭되지 않는다.

  /** 선택 가능한 저장소 백엔드와 사용 가능 여부. */
  @GetMapping("/data-store/backends")
  @RequirePermission("platform:tenant:create")
  public ResponseEntity<List<DataStoreBackendOption>> dataStoreBackends() {
    return ResponseEntity.ok(dataStoreService.availableBackends());
  }

  /** 생성 전 DBA 연결 테스트. 접속 실패도 200 {@code ok=false} 로 돌려준다. */
  @PostMapping("/data-store/test")
  @RequirePermission("platform:tenant:create")
  public ResponseEntity<ConnectionTestResult> testDataStoreConnection(
      @Valid @RequestBody DataStoreConnectionTestRequest request) {
    return ResponseEntity.ok(dataStoreService.testConnection(request));
  }

  /** 테넌트의 저장소 설정(비밀 없음). */
  @GetMapping("/{tenantId}/data-store")
  @RequirePermission("platform:tenant:read")
  public ResponseEntity<TenantDataStoreResponse> dataStore(@PathVariable long tenantId) {
    return ResponseEntity.ok(dataStoreService.detail(tenantId));
  }

  /** 저장된 계정 전부로 접속해 검증하고 모두 성공하면 READY 로 바꾼다. */
  @PostMapping("/{tenantId}/data-store/verify")
  @RequirePermission("platform:tenant:create")
  public ResponseEntity<DataStoreVerifyResponse> verifyDataStore(
      @PathVariable long tenantId, Authentication authentication) {
    return ResponseEntity.ok(
        dataStoreService.verify(tenantId, (Long) authentication.getPrincipal()));
  }

  /** PENDING 스크립트 모드 저장소의 비밀번호를 교체하고 새 스크립트를 돌려준다. */
  @PostMapping("/{tenantId}/data-store/script")
  @RequirePermission("platform:tenant:create")
  public ResponseEntity<DataStoreScriptResponse> reissueDataStoreScript(
      @PathVariable long tenantId, Authentication authentication) {
    return ResponseEntity.ok(
        dataStoreService.reissueScript(tenantId, (Long) authentication.getPrincipal()));
  }
}
