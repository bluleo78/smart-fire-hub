package com.smartfirehub.global.security;

import com.smartfirehub.global.exception.ExternalServiceException;
import com.smartfirehub.global.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * ai-agent 를 부를 때 실을 <b>대행 주체</b> 헤더를 요청 컨텍스트에서 만든다 — 그 요청을 낸 사용자와
 * 그가 보고 있던 테넌트다.
 *
 * <p>파라미터로 받지 않고 컨텍스트에서 읽는 이유: 두 값은 {@code JwtAuthenticationFilter} 가 이미
 * 요청 단위로 세워 둔 앰비언트 상태다(테넌트는 RLS 가 이 방식에 의존한다). 호출 메서드 시그니처마다
 * 같은 값을 다시 얹으면 그 상태를 이중으로 들고 다니게 된다.
 *
 * <p>컨텍스트가 비어 있으면 <b>호출하지 않고 즉시 실패한다</b>. ai-agent 는 대행 헤더가 없는 요청을
 * 400 으로 거부하므로 "헤더 없이 진행"은 완화가 아니라 확정된 실패이고, 그 실패는 원격 400 → 일반
 * 문구로 퇴화해 원인이 ai-agent 로그에만 남는다. 여기서 끊으면 사유가 api 쪽에 남는다.
 * 컨텍스트가 비는 것은 요청 경로 밖 호출(스케줄러·단위 테스트)뿐이다.
 *
 * <p>왜 공용 클래스인가: 그래프 <b>변경</b>(GraphMutationClient)과 그래프 <b>읽기</b>
 * (OntologyService#getGraph)가 같은 계약을 쓰는데, 각자 복제하면 한쪽만 고쳐져도 컴파일이 알려주지
 * 않는다 — {@link InternalCallHeaders} 를 상수로 묶은 것과 같은 이유다.
 */
public final class DelegationHeaders {
  private static final Logger log = LoggerFactory.getLogger(DelegationHeaders.class);

  private DelegationHeaders() {}

  /**
   * 현재 요청 컨텍스트의 대행 헤더를 만든다.
   *
   * @param opLabel 실패 메시지에 실을 작업 이름(예: "엔티티 병합", "지식그래프 조회")
   * @throws ExternalServiceException 컨텍스트에 사용자·테넌트가 없을 때(원격 호출 없이 즉시)
   */
  public static HttpHeaders require(String opLabel) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    Long userId =
        authentication != null && authentication.getPrincipal() instanceof Long id ? id : null;
    Long tenantId = TenantContext.get();

    if (userId == null || tenantId == null) {
      log.error(
          "[delegation] {} 호출 중단 — 요청 컨텍스트에 대행 주체가 없다(userId={}, tenantId={}).",
          opLabel,
          userId,
          tenantId);
      throw new ExternalServiceException(
          "요청의 사용자·워크스페이스 정보를 확인할 수 없어 " + opLabel + "에 실패했습니다."
              + " 다시 로그인한 뒤 시도해 주세요.");
    }

    HttpHeaders headers = new HttpHeaders();
    headers.set(InternalCallHeaders.ON_BEHALF_OF, String.valueOf(userId));
    headers.set(InternalCallHeaders.ON_BEHALF_OF_TENANT, String.valueOf(tenantId));
    return headers;
  }
}
