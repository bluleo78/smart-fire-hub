package com.smartfirehub.global.security;

import java.util.Collection;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

/**
 * 운영자(플랫폼) 평면 인증 표식.
 *
 * <p>왜 별도 타입인가: 평면 구분을 권한 문자열로 표현하면(예: {@code platform:} 접두사 검사) 누군가
 * 테넌트 롤에 {@code platform:*} 권한을 붙이는 순간 평면이 뚫린다. 권한 카탈로그({@code permission})는
 * 전역 공유라 그런 부여가 문법적으로 가능하다. 타입으로 표현하면 이 인증이 어느 <b>발급 경로</b>에서
 * 나왔는지가 런타임에 확실해지고, 평면 가드는 그것만 본다.
 *
 * <p>{@code getPrincipal()} 은 테넌트 평면과 동일하게 {@code Long userId} 를 돌려준다 — 컨트롤러가
 * 두 평면에서 같은 방식으로 사용자 id 를 꺼낼 수 있다.
 */
public class PlatformAuthentication extends UsernamePasswordAuthenticationToken {

  /**
   * 지금 요청이 <b>플랫폼 평면</b>인가. 판정 기준은 인증 <b>타입</b>이다 — 표식의 부재로 판정하지
   * 않는다(P7-a 의 양방향 함정, 그리고 {@code TenantContext} 부재로 평면을 판정하다 실패한 전례).
   *
   * <p>인증이 <b>없는</b> 경우는 {@code false} 다. 호출부가 그것을 거부로 볼지 통과로 볼지는 각자
   * 정한다 — 테넌트 HTTP 요청은 {@code JwtAuthenticationFilter} 가 반드시 인증을 채우므로 "없음"은
   * 배경 잡·부트스트랩이고, 그것을 거부로 바꾸면 배경 경로가 통째로 막힌다.
   *
   * <p>이 술어가 여기 있는 이유: 같은 {@code instanceof} 검사가 {@code PlatformPlaneFilter} 와
   * {@code SettingsService} 두 곳에 생겼다. 이 프로젝트가 반복해서 다시 배우는 규칙("평면은 인증
   * 타입으로 판정한다")이 집을 두 개 가지면, 한쪽만 고치는 순간 규칙이 갈린다.
   */
  public static boolean isCurrent() {
    return org.springframework.security.core.context.SecurityContextHolder.getContext()
            .getAuthentication()
        instanceof PlatformAuthentication;
  }

  public PlatformAuthentication(Long userId, Collection<? extends GrantedAuthority> authorities) {
    super(userId, null, authorities);
  }
}
