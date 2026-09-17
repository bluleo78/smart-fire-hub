package com.smartfirehub.global.security;

/**
 * 내부 서비스 대행 호출({@code Authorization: Internal ...})의 <b>와이어 계약 헤더 이름</b>.
 *
 * <p>왜 상수로 묶는가: 이 계약은 양쪽이 짝을 맞춰야 성립한다 — 수신측({@link
 * JwtAuthenticationFilter})과 송신측({@code GraphMutationClient} 등)이 각자 문자열 리터럴을 들고
 * 있으면 이름이 어긋나도 <b>컴파일이 알려주지 않고</b> 런타임 403/400 으로만 표면화된다. 이번
 * 장애(테넌트 전파 누락 → 권한 0개 → 403)가 정확히 그 실패 모양이었다. 같은 이유로 공개 경로
 * 목록을 묶어 둔 {@link PlatformAuthPaths} 와 동일한 관례다.
 *
 * <p>ai-agent 쪽 대응 상수는 {@code apps/firehub-ai-agent/src/constants.ts} 에 있다(언어가 달라
 * 공유가 불가능하므로 두 파일이 서로를 가리킨다).
 */
public final class InternalCallHeaders {

  /** 대행 대상 사용자 id. */
  public static final String ON_BEHALF_OF = "X-On-Behalf-Of";

  /**
   * 대행 호출의 실행 테넌트. 웹 세션 JWT 의 tenant 클레임에서 파생돼 api → ai-agent → api 로
   * 전파된다. 수신측은 이 값을 대행 대상 사용자의 ACTIVE 멤버십과 대조한 뒤에만 채택한다.
   */
  public static final String ON_BEHALF_OF_TENANT = "X-On-Behalf-Of-Tenant";

  private InternalCallHeaders() {}
}
