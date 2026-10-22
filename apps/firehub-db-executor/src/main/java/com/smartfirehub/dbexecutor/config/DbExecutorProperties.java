package com.smartfirehub.dbexecutor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 실행기 설정({@code app.*}).
 *
 * <p>내부 토큰이 비어 있으면 어떤 요청도 인증할 수 없는 서비스가 되므로, 조용히 기동하지 않고 바인딩 단계에서 실패시킨다(fail-fast).
 *
 * @param internalToken api 와 공유하는 {@code EXECUTOR_INTERNAL_TOKEN}
 * @param apiBaseUrl 저장소 기술자를 조회할 api 주소
 * @param descriptorTtlSeconds 기술자 성공 응답 캐시 수명(초)
 * @param pool 테넌트 풀 한도
 */
@ConfigurationProperties(prefix = "app")
public record DbExecutorProperties(
    String internalToken,
    @DefaultValue("http://api:8080") String apiBaseUrl,
    @DefaultValue("60") long descriptorTtlSeconds,
    @DefaultValue Pool pool) {

  public DbExecutorProperties {
    if (internalToken == null || internalToken.isBlank()) {
      throw new IllegalStateException("app.internal-token(EXECUTOR_INTERNAL_TOKEN)이 설정되지 않았습니다.");
    }
  }

  /** 내부 토큰을 로그·예외로 흘리지 않도록 마스킹한다. */
  @Override
  public String toString() {
    return "DbExecutorProperties[internalToken=****, apiBaseUrl="
        + apiBaseUrl
        + ", descriptorTtlSeconds="
        + descriptorTtlSeconds
        + ", pool="
        + pool
        + "]";
  }

  /**
   * 풀 한도. 테넌트마다 풀이 생기므로 {@code maxPools × maxSize} 가 외부 DB 세션 한도를 넘지 않게 작게 잡는다(api 와 같은 값).
   *
   * @param maxPools 동시에 유지할 풀 수(넘으면 LRU 로 닫음)
   * @param maxSize 풀당 최대 커넥션
   */
  public record Pool(@DefaultValue("8") int maxPools, @DefaultValue("4") int maxSize) {}
}
