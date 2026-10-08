package com.smartfirehub.platform.controller;

import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.platform.dto.CreatePlatformAccountRequest;
import com.smartfirehub.platform.dto.PlatformAccountResponse;
import com.smartfirehub.platform.service.PlatformAccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 운영자 평면 전역 계정 목록(WD-47)·생성(WD-46)·비활성화·재활성화(#784).
 *
 * <p><b>권한 코드를 새로 만들지 않은 이유</b>({@link PlatformUserController} 와 같은 판단): platform_role 은
 * SUPER_ADMIN 하나뿐이고 그 롤이 category='platform' 전체를 갖는다. 새 코드를 만들어도 오늘 좁혀지는 것이 없고 마이그레이션 비용만 생긴다. 조회는
 * 기존 사용자 검색과 같은 {@code platform:member:read}, 변경은 가장 가까운 정지 계열인 {@code platform:tenant:suspend} 를
 * 쓴다. <b>두 번째 플랫폼 롤이 생기는 순간 이 판단은 무효다</b> — 테넌트 정지만 받은 롤이 전역 계정까지 잠글 수 있게 되므로, 그때 {@code
 * platform:user:suspend} 를 V113 패턴으로 신설한다.
 *
 * <p>POST /deactivate·/activate 형태는 테넌트 정지/활성화({@code PlatformTenantController})와 맞춘 것이다.
 */
@RestController
@RequestMapping("/api/platform/accounts")
@RequiredArgsConstructor
public class PlatformAccountController {

  private final PlatformAccountService accountService;

  /**
   * 계정 목록(비활성 포함, WD-47). q 가 없거나 비면 전체(생성 최신순), 있으면 이메일·이름·아이디 부분일치 필터. 응답은 감사 로그 목록과 같은 {@link
   * PageResponse} 형태다(테넌트 목록은 페이지 래퍼 없는 List 라 따르지 않았다 — 계정은 수가 커질 수 있어 서버 페이지가 필요하다).
   */
  @GetMapping
  @RequirePermission("platform:member:read")
  public ResponseEntity<PageResponse<PlatformAccountResponse>> list(
      @RequestParam(required = false) String q,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ResponseEntity.ok(accountService.list(q, page, size));
  }

  /**
   * 소속 없는 새 계정 생성(WD-46). 201 + 만든 계정.
   *
   * <p>권한을 {@code platform:tenant:create} 로 둔 이유: 이 기능은 "계정이 없는 사람을 테넌트 Owner 로 세우기" 위한 테넌트 생성의
   * 앞단이다. 위 클래스 주석과 같은 판단으로 새 코드를 만들지 않는다 — 두 번째 플랫폼 롤이 생기면 {@code platform:user:create} 를 신설한다.
   */
  @PostMapping
  @RequirePermission("platform:tenant:create")
  public ResponseEntity<PlatformAccountResponse> create(
      @Valid @RequestBody CreatePlatformAccountRequest request, Authentication authentication) {
    PlatformAccountResponse created =
        accountService.create(request, (Long) authentication.getPrincipal());
    return ResponseEntity.status(HttpStatus.CREATED).body(created);
  }

  @PostMapping("/{userId}/deactivate")
  @RequirePermission("platform:tenant:suspend")
  public ResponseEntity<Void> deactivate(@PathVariable long userId, Authentication authentication) {
    accountService.deactivate(userId, (Long) authentication.getPrincipal());
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{userId}/activate")
  @RequirePermission("platform:tenant:suspend")
  public ResponseEntity<Void> activate(@PathVariable long userId, Authentication authentication) {
    accountService.reactivate(userId, (Long) authentication.getPrincipal());
    return ResponseEntity.noContent().build();
  }
}
