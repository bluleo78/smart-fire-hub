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

  public PlatformAuthentication(Long userId, Collection<? extends GrantedAuthority> authorities) {
    super(userId, null, authorities);
  }
}
