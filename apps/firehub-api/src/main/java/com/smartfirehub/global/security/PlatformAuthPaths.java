package com.smartfirehub.global.security;

import java.util.List;

/**
 * 운영자 평면의 <b>인증 없이 열려 있는 경로</b>. {@code SecurityConfig} 의 permitAll 목록과
 * {@link PlatformPlaneFilter} 의 평면검사 면제 목록이 <b>같은 상수</b>를 본다.
 *
 * <p>왜 상수로 묶는가: 두 곳이 각자 문자열 리터럴을 들고 있으면 공개 경로를 추가·삭제할 때 한쪽을
 * 빼먹는다. 그때 생기는 결과가 두 방향 모두 나쁘다 — permitAll 에만 추가하면 브라우저가 살아 있는
 * 테넌트 Bearer 를 함께 보낼 때 평면 불일치로 403 이 되어 <b>운영자가 로그인을 못 한다</b>. 면제
 * 목록에만 추가하면 인증이 필요한 경로가 평면 검사를 건너뛴다. 어느 쪽도 컴파일이 알려주지 않는다.
 *
 * <p>{@link PlatformPlaneFilter} 는 이 패턴으로 자기 매처를 만들고 {@code SecurityConfig} 는 같은
 * 문자열을 {@code requestMatchers} 에 넘긴다. 두 매처 구현이 완전히 동일하다는 보장은 없으므로
 * (기본 루트 서블릿 매핑에서 동작은 같다) {@code PlatformPlaneIsolationTest} 의 면제 경로 테스트가
 * 여전히 실제 동작을 검증한다 — 상수 공유는 <b>목록의 어긋남</b>을 없애고, 테스트는 <b>매처의
 * 어긋남</b>을 잡는다.
 */
public final class PlatformAuthPaths {

  /** 운영자 평면 전체의 경로 접두어 패턴. */
  public static final String PLATFORM_PATTERN = "/api/platform/**";

  /** 인증 없이 호출되는 운영자 경로. 로그인과 토큰 갱신뿐이다. */
  public static final List<String> PUBLIC_PATTERNS =
      List.of("/api/platform/auth/login", "/api/platform/auth/refresh");

  private PlatformAuthPaths() {}
}
