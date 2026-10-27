package com.smartfirehub.dbexecutor.config;

import com.smartfirehub.dbexecutor.security.ScriptToken;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
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
 * @param scriptTokenKey 스크립트 1회용 토큰 서명 키({@code DB_EXECUTOR_SCRIPT_TOKEN_KEY}, api 와 같은 값) — {@code
 *     Authorization: Script} 토큰을 검증한다(WD-78). 비우면 스크립트 자격을 열지 않는다(언제나 401). 값이 있으면 UTF-8 32바이트
 *     이상이어야 하고, 짧으면 기동이 실패한다.
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

  /**
   * {@code .env.example} 자리표시자의 접두사(최종 리뷰 I2). 이 접두사로 시작하는 값은 예시를 그대로 옮긴 것이라 누구나 안다 — 알려진 기본값과 같이
   * 거부한다(compose 의 {@code :?} 는 빈 값만 막는다).
   */
  static final String PLACEHOLDER_PREFIX = "change-me";

  /** 알려진 개발 기본값이거나 {@code .env.example} 자리표시자인가(null 은 아니다 — 비움은 각 자리의 규칙을 따른다). */
  static boolean isInsecureDefault(String value) {
    return value != null
        && (INSECURE_DEFAULT_TOKENS.contains(value) || value.startsWith(PLACEHOLDER_PREFIX));
  }

  public DbExecutorProperties {
    if (internalToken == null || internalToken.isBlank()) {
      throw new IllegalStateException(
          "app.internal-token(DB_EXECUTOR_INTERNAL_TOKEN)이 설정되지 않았습니다.");
    }
    // 알려진 기본값·자리표시자면 기동을 막는다 — 운영 설정 누락이 조용히 추측 가능한 자격으로 이어지지 않게(로컬·테스트만 허용 플래그).
    if (!allowDefaultToken && (isInsecureDefault(internalToken) || isInsecureDefault(loadToken))) {
      throw new IllegalStateException(
          "내부 토큰 또는 적재 토큰이 알려진 개발 기본값입니다 — 운영에서는 안전한 값으로 바꾸세요"
              + "(로컬 개발만 app.allow-default-token=true 로 허용합니다).");
    }
    // 서명 키(WD-78, 최종 리뷰 M2): 비우면 스크립트 자격을 열지 않는다(언제나 401 — 허용). 값이 있는데 짧으면 검증이 늘 실패해
    // 조용한 401 만 남으므로 기동 단계에서 막는다(api ScriptTokenIssuer 가 발급 때 거는 것과 같은 UTF-8 바이트 기준). 허용 플래그와
    // 무관하다. 자리표시자(change-me…)는 길이 검사를 통과하므로 알려진 기본값처럼 따로 거부한다.
    if (scriptTokenKey != null && !scriptTokenKey.isBlank()) {
      if (scriptTokenKey.getBytes(StandardCharsets.UTF_8).length < ScriptToken.MIN_KEY_BYTES) {
        throw new IllegalStateException(
            "app.script-token-key(DB_EXECUTOR_SCRIPT_TOKEN_KEY)가 "
                + ScriptToken.MIN_KEY_BYTES
                + "바이트보다 짧습니다 — 32바이트 이상 임의 값(api 와 같은 값)으로 설정하세요.");
      }
      if (!allowDefaultToken && isInsecureDefault(scriptTokenKey)) {
        throw new IllegalStateException(
            "스크립트 토큰 서명 키가 알려진 개발 기본값입니다 — 운영에서는 안전한 값으로 바꾸세요"
                + "(로컬 개발만 app.allow-default-token=true 로 허용합니다).");
      }
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
   * WD-76)은 서로 다른 풀 묶음이고 각각 {@code maxPools} 개까지 유지한다. 슬롯 풀(WD-78, 스크립트 슬롯 조회)은 (테넌트, 슬롯)마다 크기
   * {@code slotMaxSize} 이고 전체 {@code slotMaxPools} 개까지 유지한다. 그래서 최악의 동시 세션 수는 {@code maxPools ×
   * (maxSize + readOnlyMaxSize) + slotMaxPools × slotMaxSize} 이다(기본값 8 × (4 + 2) + 8 × 3 = 72). 이
   * 값이 외부 DB 세션 한도를 넘지 않게 작게 잡는다({@code maxPools}·{@code maxSize} 는 api 와 같은 값). 대여 중인 풀이 퇴역하면 잠시
   * 넘을 수 있다({@code TenantPools}).
   *
   * @param maxPools 동시에 유지할 풀 수(넘으면 LRU 로 닫음)
   * @param maxSize 풀당 최대 커넥션(OWNER 풀)
   * @param readOnlyMaxSize 읽기 전용(분석 조회) 풀당 최대 커넥션 — 조회는 짧고 동시성이 낮아 작게 둔다
   * @param slotMaxPools 슬롯 풀 전체 상한 — 전 테넌트·전 슬롯 합계(풀마다 크기는 {@code slotMaxSize})
   * @param slotMaxSize 슬롯 풀당 최대 커넥션 — 기본 3(Ruling 17), env {@code DB_EXECUTOR_SLOT_MAX_SIZE}, 1 이상
   */
  public record Pool(
      @DefaultValue("8") int maxPools,
      @DefaultValue("4") int maxSize,
      @DefaultValue("2") int readOnlyMaxSize,
      @DefaultValue("8") int slotMaxPools,
      @DefaultValue("3") int slotMaxSize) {

    /** 설정 바인딩이 쓰는 주 생성자(생성자가 둘이라 바인딩 대상을 명시한다). */
    @ConstructorBinding
    public Pool {
      // 0 이하면 첫 슬롯 조회 때 Hikari 가 예외를 낸다 — 실행 중이 아니라 기동(바인딩) 시점에 막는다(리뷰 M-7).
      if (slotMaxSize < 1) {
        throw new IllegalArgumentException("app.pool.slot-max-size 는 1 이상이어야 합니다: " + slotMaxSize);
      }
    }

    /** 슬롯 풀 상한(8)·크기(3)를 기본값으로 두는 편의 생성자(코드·테스트에서 직접 만들 때). */
    public Pool(int maxPools, int maxSize, int readOnlyMaxSize) {
      this(maxPools, maxSize, readOnlyMaxSize, 8, 3);
    }
  }
}
