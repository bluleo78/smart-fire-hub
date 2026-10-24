package com.smartfirehub.dbexecutor.config;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 실행기 설정({@code app.*}).
 *
 * <p>내부 토큰이 비어 있으면 어떤 요청도 인증할 수 없는 서비스가 되므로, 조용히 기동하지 않고 바인딩 단계에서 실패시킨다(fail-fast).
 *
 * @param internalToken api 와 공유하는 db-executor 내부 토큰({@code DB_EXECUTOR_INTERNAL_TOKEN}) — 전 창구와 api
 *     기술자 조회에 쓴다. Python 실행기의 {@code EXECUTOR_INTERNAL_TOKEN} 과는 별개 값이다(스펙 §3.4).
 * @param loadToken Python 실행기가 쓰는 적재 전용 토큰({@code DB_EXECUTOR_LOAD_TOKEN}) — {@code /execute/load}
 *     만 허용한다. 비우면 적재 자격을 열지 않는다.
 * @param scriptTokenKey 스크립트 1회용 토큰 서명 키({@code DB_EXECUTOR_SCRIPT_TOKEN_KEY}) — 검증은 이후 작업에서
 *     구현한다(지금은 바인딩만).
 * @param apiBaseUrl 저장소 기술자를 조회할 api 주소
 * @param descriptorTtlSeconds 기술자 성공 응답 캐시 수명(초)
 * @param pool 테넌트 풀 한도
 * @param allowDefaultToken 알려진 개발 기본 토큰을 허용할지 — 로컬·테스트 프로필에서만 켠다(api 의 {@code
 *     app.executor.allow-default-token} 과 같은 패턴)
 */
@ConfigurationProperties(prefix = "app")
public record DbExecutorProperties(
    String internalToken,
    String loadToken,
    String scriptTokenKey,
    @DefaultValue("http://api:8080") String apiBaseUrl,
    @DefaultValue("60") long descriptorTtlSeconds,
    @DefaultValue Pool pool,
    @DefaultValue("false") boolean allowDefaultToken) {

  /** 설정 파일·compose 에 박혀 있는 알려진 개발 기본 토큰 — 누구나 추측할 수 있어 운영에서 거부한다. */
  static final Set<String> INSECURE_DEFAULT_TOKENS =
      Set.of("dev-db-executor-token", "dev-db-executor-load-token");

  public DbExecutorProperties {
    if (internalToken == null || internalToken.isBlank()) {
      throw new IllegalStateException(
          "app.internal-token(DB_EXECUTOR_INTERNAL_TOKEN)이 설정되지 않았습니다.");
    }
    // 알려진 기본값이면 기동을 막는다 — 운영 설정 누락이 조용히 추측 가능한 자격으로 이어지지 않게(로컬·테스트만 허용 플래그).
    if (!allowDefaultToken
        && (INSECURE_DEFAULT_TOKENS.contains(internalToken)
            || (loadToken != null && INSECURE_DEFAULT_TOKENS.contains(loadToken)))) {
      throw new IllegalStateException(
          "내부 토큰 또는 적재 토큰이 알려진 개발 기본값입니다 — 운영에서는 안전한 값으로 바꾸세요"
              + "(로컬 개발만 app.allow-default-token=true 로 허용합니다).");
    }
    // 두 자격이 같으면 적재 토큰을 가진 쪽이 전 창구를 쓸 수 있게 되어 범위 분리가 무너진다 — 설정 실수로 보고 기동을 막는다.
    if (loadToken != null && !loadToken.isBlank() && loadToken.equals(internalToken)) {
      throw new IllegalStateException(
          "app.load-token(DB_EXECUTOR_LOAD_TOKEN)이 내부 토큰과 같습니다 — 서로 다른 값이어야 합니다.");
    }
  }

  /** 내부 토큰을 로그·예외로 흘리지 않도록 마스킹한다. */
  @Override
  public String toString() {
    return "DbExecutorProperties[internalToken=****, loadToken=****, scriptTokenKey=****, apiBaseUrl="
        + apiBaseUrl
        + ", descriptorTtlSeconds="
        + descriptorTtlSeconds
        + ", pool="
        + pool
        + "]";
  }

  /**
   * 풀 한도. 테넌트마다 풀이 생긴다. OWNER 풀({@code /execute/sql}·probe)과 읽기 전용 풀({@code /execute/query},
   * WD-76)은 서로 다른 풀 묶음이고 각각 {@code maxPools} 개까지 유지하므로, 최악의 동시 세션 수는 {@code maxPools × (maxSize +
   * readOnlyMaxSize)} 다(기본값 8 × (4 + 2) = 48). 이 값이 외부 DB 세션 한도를 넘지 않게 작게 잡는다({@code
   * maxPools}·{@code maxSize} 는 api 와 같은 값).
   *
   * @param maxPools 동시에 유지할 풀 수(넘으면 LRU 로 닫음)
   * @param maxSize 풀당 최대 커넥션(OWNER 풀)
   * @param readOnlyMaxSize 읽기 전용(분석 조회) 풀당 최대 커넥션 — 조회는 짧고 동시성이 낮아 작게 둔다
   */
  public record Pool(
      @DefaultValue("8") int maxPools,
      @DefaultValue("4") int maxSize,
      @DefaultValue("2") int readOnlyMaxSize) {}
}
