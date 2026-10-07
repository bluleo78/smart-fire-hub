package com.smartfirehub.securitylevel.access;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * {@code /api/v1/datasets/{id|datasetId}/**} 의 모든 핸들러 앞에서 VIEW 를 강제한다(스펙 §4.2 2행 + 쓰기 경로 보강).
 *
 * <p>왜 서비스가 아니라 인터셉터인가: 데이터셋 하위 엔드포인트가 9개 컨트롤러·40여 개에 흩어져 있고, 일부(DocumentChunk·FileObject)는
 * DatasetRepository 를 거치지도 않는다. 한 곳에서 경로 변수로 판정하면 새 엔드포인트도 자동으로 보호된다 — DatasetRouteHidingTest 가 그
 * 성질을 열거로 고정한다.
 *
 * <p>순서: PermissionInterceptor 다음에 등록한다. 권한이 없는 사용자는 존재 여부와 무관하게 403 을 받으므로 정보가 새지 않는다.
 */
@Component
@RequiredArgsConstructor
public class DatasetAccessInterceptor implements HandlerInterceptor {

  private final DatasetAccessGuard guard;

  @Override
  public boolean preHandle(
      @NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull Object handler) {
    // SSE 등 비동기 재디스패치는 최초 디스패치에서 이미 판정했다.
    if (request.getDispatcherType() == DispatcherType.ASYNC
        || !(handler instanceof HandlerMethod)) {
      return true;
    }
    // 인증이 없는 요청은 판정 대상이 아니다 — 보안 필터 체인이 이미 401 로 막으며, 여기서 자격을 계산하면
    // 테넌트 컨텍스트 부재로 500 이 날 수 있다. 숨김 판정은 인증된 요청에만 건다.
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !(auth.getPrincipal() instanceof Long)) {
      return true;
    }
    @SuppressWarnings("unchecked")
    Map<String, String> vars =
        (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
    if (vars == null) {
      return true;
    }
    String raw = vars.containsKey("datasetId") ? vars.get("datasetId") : vars.get("id");
    if (raw == null) {
      return true;
    }
    long datasetId;
    try {
      datasetId = Long.parseLong(raw);
    } catch (NumberFormatException e) {
      // 숫자가 아니면 Spring 이 타입 불일치 400 을 낸다 — 판정할 데이터셋이 없다.
      return true;
    }
    guard.requireView(datasetId);
    return true;
  }
}
