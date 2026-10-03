package com.smartfirehub.global.security;

import com.smartfirehub.global.exception.CodedApiException;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 첫 로그인 비밀번호 변경 강제 게이트(WD-2).
 *
 * <p>JwtAuthenticationFilter 가 토큰 클레임 pwc 를 {@link #MUST_CHANGE_PASSWORD_ATTR} 요청 속성으로 옮겨 두면, 이
 * 인터셉터는 {@link AllowedDuringPasswordChange} 가 없는 핸들러를 403 PASSWORD_CHANGE_REQUIRED 로 막는다.
 * PermissionInterceptor <b>앞</b>에 등록한다 — 권한 부족(403 코드 없음)보다 이 사유가 먼저 보여야 웹이 변경 화면으로 보낼 수 있다.
 *
 * <p>내부 호출(Internal 토큰 + X-On-Behalf-Of)은 속성을 세우지 않으므로 통과한다. 웹 경로 위임은 그 시작(웹 API)이 여기서 막힌다;
 * Slack·예약 등 비웹 채널은 pwc 대상이 아니다(스펙 §3).
 */
@Component
public class PasswordChangeInterceptor implements HandlerInterceptor {

  /** 필터가 세우는 요청 속성 이름. 값은 Boolean.TRUE. */
  public static final String MUST_CHANGE_PASSWORD_ATTR = "firehub.mustChangePassword";

  /** 강제 대상 요청이 허용 핸들러가 아니면 403 PASSWORD_CHANGE_REQUIRED 로 막는다. */
  @Override
  public boolean preHandle(
      @NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull Object handler) {
    // SSE 등 비동기 재디스패치는 최초 요청에서 이미 판정됐다(PermissionInterceptor 와 동일).
    if (request.getDispatcherType() == DispatcherType.ASYNC) {
      return true;
    }
    if (!Boolean.TRUE.equals(request.getAttribute(MUST_CHANGE_PASSWORD_ATTR))) {
      return true;
    }
    if (handler instanceof HandlerMethod hm
        && hm.hasMethodAnnotation(AllowedDuringPasswordChange.class)) {
      return true;
    }
    throw new CodedApiException(
        HttpStatus.FORBIDDEN, "PASSWORD_CHANGE_REQUIRED", "비밀번호를 변경해야 계속할 수 있습니다");
  }
}
