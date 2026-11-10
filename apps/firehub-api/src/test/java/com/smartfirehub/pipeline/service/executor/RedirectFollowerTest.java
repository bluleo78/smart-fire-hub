package com.smartfirehub.pipeline.service.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

/** RedirectFollower 단위 테스트(WD-693). */
class RedirectFollowerTest {

  /**
   * 자동 리다이렉트를 끈 빌더는 받은 빌더의 복제본이다 — 주입받은 공유 빌더를 바꾸면 응답 크기 제한이 다른 파이프라인이 동시에 돌 때 서로의 설정(커넥터·코덱)을
   * 덮어쓴다. 호출자가 clone() 을 잊어도 안전하게 follower 안에서 복제한다.
   */
  @Test
  void withoutAutoRedirect_clonesAndNeverMutatesGivenBuilder() {
    WebClient.Builder shared = mock(WebClient.Builder.class);
    WebClient.Builder copy = mock(WebClient.Builder.class);
    when(shared.clone()).thenReturn(copy);
    when(copy.clientConnector(any())).thenReturn(copy);

    WebClient.Builder result =
        RedirectFollower.withoutAutoRedirect(shared, new SsrfSafeHttpClientFactory(address -> {}));

    assertThat(result).isSameAs(copy);
    verify(shared, never()).clientConnector(any());
    verify(copy).clientConnector(any());
  }
}
