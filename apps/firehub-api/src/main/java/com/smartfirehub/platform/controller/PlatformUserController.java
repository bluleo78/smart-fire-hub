package com.smartfirehub.platform.controller;

import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.platform.dto.PlatformUserResponse;
import com.smartfirehub.platform.service.PlatformUserService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 운영자 평면 사용자 검색.
 *
 * <p><b>왜 이 엔드포인트가 존재하나</b>: {@code CreateTenantRequest.ownerUserId} 가 숫자라
 * 이것이 없으면 테넌트 생성 화면이 "사용자 ID 를 숫자로 입력하세요"가 되고, 운영자는 DB 를 직접
 * 조회해야 한다 — 운영자 콘솔의 주요 워크플로가 사용 불가가 된다(P7-c2a, R-3).
 *
 * <p><b>왜 권한 코드를 새로 만들지 않았나</b>: {@code platform_role} 에는 {@code SUPER_ADMIN}
 * 하나뿐이고 그 롤이 {@code category='platform'} 전체를 이미 갖는다(V82/V113). 즉 새 코드를
 * 만들어도 오늘 좁혀지는 것이 없고 마이그레이션 비용만 생긴다. <b>두 번째 플랫폼 롤이 생기는
 * 순간 이 판단은 무효다</b> — 멤버 조회만 받은 롤이 전 사용자 열거까지 얻게 되므로, 그때
 * {@code platform:user:read} 를 신설하고 V113 패턴(코드 명시 + ON CONFLICT DO NOTHING)을 따른다.
 */
@RestController
@RequestMapping("/api/platform/users")
@RequiredArgsConstructor
public class PlatformUserController {

  private final PlatformUserService userService;

  /**
   * 이메일/이름 부분일치 검색. 하한 2자, 상한 20건.
   *
   * <p>{@code q} 를 {@code required = false} 로 받는다 — {@code required = true}(기본값)이면
   * 파라미터 부재 시 Spring 이 {@code MissingServletRequestParameterException} 을 던지는데, 이
   * 코드베이스의 {@code GlobalExceptionHandler} 에는 그 예외 전용 핸들러가 없어 범용
   * {@code Exception} 핸들러로 떨어져 <b>500</b> 이 난다(실측). 대신 {@code null} 을 그대로
   * 서비스에 넘기면 서비스의 하한 검사가 {@link IllegalArgumentException} 을 던지고, 그건 이미
   * 전역 핸들러가 400 으로 바꾼다 — 새 핸들러를 추가하지 않고도 "파라미터를 잊은 호출"이 전
   * 사용자 덤프로 성공하는 일을 막는다.
   */
  @GetMapping
  @RequirePermission("platform:member:read")
  public ResponseEntity<List<PlatformUserResponse>> search(
      @RequestParam(required = false) String q) {
    return ResponseEntity.ok(userService.search(q));
  }
}
