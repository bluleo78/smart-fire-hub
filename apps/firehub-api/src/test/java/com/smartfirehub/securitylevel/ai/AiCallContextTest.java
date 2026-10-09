package com.smartfirehub.securitylevel.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartfirehub.securitylevel.access.ProviderHosting;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * AI 문맥 판별(스펙 §4.3): 표시 없는 요청은 비AI, 표시된 요청은 채팅 호스팅, share 목적은 공유 호스팅(채팅·임베딩 모두 자체 호스팅이어야 자체 호스팅),
 * none 은 비AI, ThreadLocal 범위는 요청 표시보다 우선하고 끝나면 복원된다.
 */
class AiCallContextTest {

  private final AiHostingResolver resolver = mock(AiHostingResolver.class);
  private final AiCallContext ctx = new AiCallContext(resolver);

  @AfterEach
  void clear() {
    RequestContextHolder.resetRequestAttributes();
  }

  /** 새 요청을 만들어 현재 스레드의 요청 속성으로 묶는다. */
  private MockHttpServletRequest bind() {
    MockHttpServletRequest req = new MockHttpServletRequest();
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    return req;
  }

  @Test
  void noRequest_isNotAi() {
    assertThat(ctx.current()).isEmpty();
  }

  @Test
  void unmarkedRequest_isNotAi() {
    bind();
    assertThat(ctx.current()).isEmpty();
  }

  @Test
  void markedRequest_usesChatHosting_andShareUsesForShareHosting() {
    when(resolver.chat()).thenReturn(ProviderHosting.SELF_HOSTED);
    when(resolver.forShare()).thenReturn(ProviderHosting.EXTERNAL); // 임베딩은 외부인 혼합 구성
    MockHttpServletRequest req = bind();
    AiCallContext.markAiRequest(req, null);
    assertThat(ctx.current()).contains(new AiCall(ProviderHosting.SELF_HOSTED, false));
    MockHttpServletRequest req2 = bind();
    AiCallContext.markAiRequest(req2, " Share ");
    assertThat(ctx.current())
        .as("공유 목적은 임베딩까지 자체 호스팅이어야 자체 호스팅")
        .contains(new AiCall(ProviderHosting.EXTERNAL, true));
  }

  @Test
  void hosting_isComputedOncePerRequest() {
    when(resolver.chat()).thenReturn(ProviderHosting.EXTERNAL);
    MockHttpServletRequest req = bind();
    AiCallContext.markAiRequest(req, "");
    ctx.current();
    when(resolver.chat()).thenReturn(ProviderHosting.SELF_HOSTED);
    assertThat(ctx.current())
        .as("같은 요청 안에서는 첫 계산값을 캐시한다")
        .contains(new AiCall(ProviderHosting.EXTERNAL, false));
  }

  @Test
  void purposeNone_isNotAi() {
    MockHttpServletRequest req = bind();
    AiCallContext.markAiRequest(req, "none");
    assertThat(ctx.current()).isEmpty();
  }

  @Test
  void scoped_overridesAndRestores() {
    AiCall call = new AiCall(ProviderHosting.EXTERNAL, true);
    assertThat(ctx.callWith(call, ctx::current)).contains(call);
    assertThat(ctx.current()).isEmpty();
    // 중첩 범위: 안쪽이 끝나면 바깥 범위로 돌아온다.
    AiCall inner = new AiCall(ProviderHosting.SELF_HOSTED, false);
    ctx.runWith(
        call,
        () -> {
          assertThat(ctx.callWith(inner, ctx::current)).contains(inner);
          assertThat(ctx.current()).contains(call);
        });
    assertThat(ctx.current()).isEmpty();
  }
}
