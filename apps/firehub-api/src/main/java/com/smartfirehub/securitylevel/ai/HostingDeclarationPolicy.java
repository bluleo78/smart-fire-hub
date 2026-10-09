package com.smartfirehub.securitylevel.ai;

import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.permission.service.PermissionService;
import com.smartfirehub.securitylevel.SecurityPermissions;
import com.smartfirehub.securitylevel.access.ProviderHosting;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 공급자 호스팅 위치 선언 권한(스펙 §2.6 — security:settings 가 "공급자 호스팅 위치 선언"을 포함). 자격증명 화면 자체는 ai:settings 라서,
 * 같은 저장 요청 안에서 "자체 호스팅으로 올리는" 변경만 따로 막는다 — 오선언은 민감 데이터를 외부로 보내는 결정이기 때문이다(스펙 §7.6).
 */
@Component
@RequiredArgsConstructor
public class HostingDeclarationPolicy {

  /** 자체 호스팅 선언 권한 부족 응답 코드(403). */
  public static final String FORBIDDEN_CODE = "HOSTING_DECLARATION_FORBIDDEN";

  private final PermissionService permissionService;

  /**
   * 전송 대상 변경이 없는 판정 — {@link #requireChangeAllowed(Long, ProviderHosting, ProviderHosting,
   * boolean)} 참고.
   */
  public void requireChangeAllowed(Long userId, ProviderHosting before, ProviderHosting after) {
    requireChangeAllowed(userId, before, after, false);
  }

  /**
   * "자체 호스팅으로 올리는" 변경이면 security:settings 를 요구한다. 올리는 변경은 두 가지다: EXTERNAL→SELF_HOSTED, 그리고 결과가
   * SELF_HOSTED 인 채 전송 대상(공급자·Base URL 등)이 바뀌는 것 — 후자는 이전 선언이 옛 목적지에 대한 것이라, 새 목적지를 자체 호스팅이라 말하는 것은
   * 새 선언이기 때문이다(막지 않으면 ai:settings 만으로 선언을 유지한 채 목적지를 외부로 돌릴 수 있다). 내리거나 그대로면 통과(더 보수적인 방향). {@code
   * userId} 는 null 을 받는다(사용자 없는 내부 저장 경로) — 이때 올리는 변경은 권한을 확인할 수 없으므로 거부한다(fail-closed).
   */
  public void requireChangeAllowed(
      Long userId, ProviderHosting before, ProviderHosting after, boolean targetChanged) {
    if (after != ProviderHosting.SELF_HOSTED
        || (before == ProviderHosting.SELF_HOSTED && !targetChanged)) {
      return;
    }
    if (userId == null
        || !permissionService.getUserPermissions(userId).contains(SecurityPermissions.SETTINGS)) {
      throw new CodedApiException(
          HttpStatus.FORBIDDEN, FORBIDDEN_CODE, "자체 호스팅 선언에는 보안 설정 권한(security:settings)이 필요합니다");
    }
  }
}
