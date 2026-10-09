package com.smartfirehub.securitylevel.ai;

import com.smartfirehub.securitylevel.access.ProviderHosting;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * "지금 데이터가 AI 로 가는가"를 알려 준다(스펙 §4.3 — Internal + X-On-Behalf-Of 를 AI 경로로 식별).
 *
 * <p>요청 경로: JwtAuthenticationFilter 가 {@link #markAiRequest} 로 요청 속성을 남긴다. 호스팅은 첫 조회 때 채팅 자격증명에서
 * 계산해 요청 속성에 캐시한다(agent 가 보낸 값은 쓰지 않는다). 배경 경로(Proactive 컨텍스트 수집 등): 요청이 없으므로 {@link #callWith} 의
 * ThreadLocal 범위가 대신한다 — 범위가 요청 표시보다 우선한다.
 */
@Component
@RequiredArgsConstructor
public class AiCallContext {

  /** 요청 속성: 내부 대행 인증이 성공한 AI 경로 요청이다(Boolean.TRUE). */
  public static final String AI_CALL_ATTR = AiCallContext.class.getName() + ".aiCall";

  /** 요청 속성: 공유 목적(X-AI-Purpose: share) 요청이다(Boolean.TRUE). */
  public static final String AI_SHARE_ATTR = AiCallContext.class.getName() + ".share";

  /** 요청 속성: 이 요청에서 계산한 호스팅 캐시 — 요청 중 설정이 바뀌어도 한 요청 안의 판정이 흔들리지 않게. */
  static final String HOSTING_CACHE_ATTR = AiCallContext.class.getName() + ".hosting";

  /** X-AI-Purpose 값: 결과가 공유 저장소·발송으로 간다 — SHARE 도 요구. */
  public static final String PURPOSE_SHARE = "share";

  /** X-AI-Purpose 값: LLM 을 거치지 않는 대행(웹 그래프 뷰어·HITL 승인) — AI 경로로 표시하지 않는다. */
  public static final String PURPOSE_NONE = "none";

  private static final ThreadLocal<AiCall> SCOPED = new ThreadLocal<>();

  private final AiHostingResolver hostingResolver;

  /**
   * 내부 대행 인증이 성공한 요청에 AI 경로 표시를 남긴다. purpose 가 none 이면 표시하지 않는다. 알 수 없는 값은 채팅(표시만)으로 본다 — 더 좁히는
   * 방향(share)이 아니면 기본 AI 판정을 받게 해 fail-closed 를 유지한다.
   */
  public static void markAiRequest(HttpServletRequest request, String purposeHeader) {
    String purpose = purposeHeader == null ? "" : purposeHeader.trim();
    if (PURPOSE_NONE.equalsIgnoreCase(purpose)) {
      return;
    }
    request.setAttribute(AI_CALL_ATTR, Boolean.TRUE);
    if (PURPOSE_SHARE.equalsIgnoreCase(purpose)) {
      request.setAttribute(AI_SHARE_ATTR, Boolean.TRUE);
    }
  }

  /** 현재 AI 문맥. 비AI 면 empty. */
  public Optional<AiCall> current() {
    AiCall scoped = SCOPED.get();
    if (scoped != null) {
      return Optional.of(scoped);
    }
    RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
    if (attrs == null
        || !Boolean.TRUE.equals(
            attrs.getAttribute(AI_CALL_ATTR, RequestAttributes.SCOPE_REQUEST))) {
      return Optional.empty();
    }
    boolean share =
        Boolean.TRUE.equals(attrs.getAttribute(AI_SHARE_ATTR, RequestAttributes.SCOPE_REQUEST));
    ProviderHosting h =
        (ProviderHosting) attrs.getAttribute(HOSTING_CACHE_ATTR, RequestAttributes.SCOPE_REQUEST);
    if (h == null) {
      // 공유 목적(GraphRAG)은 임베딩 공급자로도 데이터가 가므로 채팅·임베딩 모두 자체 호스팅일 때만 자체
      // 호스팅(AiHostingResolver#forShare).
      h = share ? hostingResolver.forShare() : hostingResolver.chat();
      attrs.setAttribute(HOSTING_CACHE_ATTR, h, RequestAttributes.SCOPE_REQUEST);
    }
    return Optional.of(new AiCall(h, share));
  }

  /** 배경 작업을 AI 문맥 안에서 실행한다. 끝나면 이전 범위로 되돌린다(중첩 허용). */
  public <T> T callWith(AiCall call, Supplier<T> work) {
    AiCall prev = SCOPED.get();
    SCOPED.set(call);
    try {
      return work.get();
    } finally {
      if (prev == null) {
        SCOPED.remove();
      } else {
        SCOPED.set(prev);
      }
    }
  }

  /** {@link #callWith} 의 반환값 없는 형태. */
  public void runWith(AiCall call, Runnable work) {
    callWith(
        call,
        () -> {
          work.run();
          return null;
        });
  }
}
