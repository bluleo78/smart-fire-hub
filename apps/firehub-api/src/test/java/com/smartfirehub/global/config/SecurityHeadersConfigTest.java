package com.smartfirehub.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link SecurityConfig} 보안 헤더 구성 회귀 테스트(WD-443).
 *
 * <p>보안 헤더는 요청 처리 전에 요청 스레드에서 쓴다(eager). lazy 면 StreamingResponseBody·SseEmitter 의 executor
 * flush(커밋)와 요청 스레드의 필터 finally 가 같은 응답 헤더 맵을 동시에 써서 간헐적으로 ConcurrentModificationException 이 났다.
 * 경합은 부하에서만 드러나므로 요청 결과가 아닌 컨텍스트의 필터 설정 자체를 확인한다.
 */
class SecurityHeadersConfigTest extends IntegrationTestBase {

  @Autowired private FilterChainProxy filterChainProxy;

  /** 모든 보안 필터 체인의 HeaderWriterFilter 가 eager 로 설정돼 있다(공개 getter 가 없어 private 필드를 읽는다). */
  @Test
  void securityHeaders_areWrittenEagerly() {
    List<HeaderWriterFilter> filters =
        filterChainProxy.getFilterChains().stream()
            .flatMap(chain -> chain.getFilters().stream())
            .filter(HeaderWriterFilter.class::isInstance)
            .map(HeaderWriterFilter.class::cast)
            .toList();
    assertThat(filters).isNotEmpty();
    // 필드가 사라지면 getField 가 이름을 담아 예외를 던져 원인이 드러난다.
    assertThat(filters)
        .allSatisfy(
            f ->
                assertThat(ReflectionTestUtils.getField(f, "shouldWriteHeadersEagerly"))
                    .isEqualTo(true));
  }
}
