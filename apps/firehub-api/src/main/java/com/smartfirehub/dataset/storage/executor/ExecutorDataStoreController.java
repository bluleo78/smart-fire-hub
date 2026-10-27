package com.smartfirehub.dataset.storage.executor;

import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.global.security.InternalTokens;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * executor 전용 저장소 기술자 엔드포인트(WD-75).
 *
 * <p>평문 DB 비밀번호를 내므로 {@code /api/} 밖 {@code /internal/executor/**} 에 둔다(web·admin nginx 는 {@code
 * /api/} 만 프록시한다). 인증은 {@code Authorization: Internal <token>} 을 이 컨트롤러가 직접 검증하며, 토큰에 따라 줄 수 있는 범위가
 * 다르다(스펙 §3.4).
 *
 * <ul>
 *   <li>{@code app.executor.internal-token}(Python 실행기): backend 와 tenantId 만 — Tibero 여도 비밀·접속 정보를
 *       주지 않는다. Python 실행기는 Tibero 에 접속하지 않고 이 값으로 거부 여부만 판단한다.
 *   <li>{@code app.db-executor.internal-token}(db-executor): 현행 전체 기술자(OWNER, {@code include} 에
 *       {@code ro} 가 있으면 RO, {@code slots} 가 있으면 슬롯 계정 S1..S10 — WD-78). 이 토큰이 비었거나 알려진 기본값({@code
 *       change-me} 자리표시자 포함)이면 평문 비밀번호를 내지 않고 503 으로 거부한다(R-token) — 로컬·테스트는 {@code
 *       app.executor.allow-default-token=true} 로만 허용한다.
 * </ul>
 *
 * <p>두 토큰이 같으면 Python 실행기가 전체 기술자를 받게 되므로 기동 시 실패시킨다(설정 실수 방지).
 */
@RestController
public class ExecutorDataStoreController {

  private static final Logger log = LoggerFactory.getLogger(ExecutorDataStoreController.class);

  /** 설정 파일·compose 에 박혀 있는 알려진 기본 토큰. 이 값이면 누구나 추측할 수 있으므로 운영에서 거부한다. */
  static final Set<String> INSECURE_DEFAULT_TOKENS =
      Set.of("dev-executor-token", "executor-token", "dev-db-executor-token");

  /**
   * {@code .env.example} 자리표시자의 접두사(최종 리뷰 I2). 예시 값을 그대로 옮긴 토큰은 누구나 알므로 알려진 기본값과 같이 비밀을 내지 않는다
   * (compose 의 {@code :?} 는 빈 값만 막는다).
   */
  static final String PLACEHOLDER_PREFIX = "change-me";

  /** 토큰이 없거나 안전하지 않아 비밀번호를 내줄 수 없을 때의 503 코드 — 컨트롤러와 서비스가 함께 쓴다. */
  public static final String TOKEN_NOT_CONFIGURED_CODE = "EXECUTOR_TOKEN_NOT_CONFIGURED";

  /** {@code ?include=} 의 값 — 읽기 전용 계정(WD-76). */
  static final String INCLUDE_RO = "ro";

  /** {@code ?include=} 의 값 — 읽기 슬롯 계정 S1..S10(WD-78). */
  static final String INCLUDE_SLOTS = "slots";

  /** include 로 받을 수 있는 값 전체. 이 밖의 값이 하나라도 섞이면 아무것도 싣지 않는다. */
  private static final Set<String> KNOWN_INCLUDES = Set.of(INCLUDE_RO, INCLUDE_SLOTS);

  private final ExecutorDataStoreDescriptorService service;

  /** Python 실행기 토큰 — 종류만 받는다. */
  private final String executorToken;

  /** db-executor 토큰 — 전체 기술자를 받는다. */
  private final String token;

  private final boolean allowDefaultToken;

  public ExecutorDataStoreController(
      ExecutorDataStoreDescriptorService service,
      @Value("${app.executor.internal-token:}") String executorToken,
      @Value("${app.db-executor.internal-token:}") String token,
      @Value("${app.executor.allow-default-token:false}") boolean allowDefaultToken) {
    // 같은 값이면 Python 실행기가 db-executor 전권(OWNER 비밀번호 포함)을 받는다 — 조용히 기동하지 않고 막는다.
    if (!executorToken.isBlank() && executorToken.equals(token)) {
      throw new IllegalStateException(
          "app.executor.internal-token 과 app.db-executor.internal-token 이 같습니다 — 서로 다른 값이어야 합니다.");
    }
    this.service = service;
    this.executorToken = executorToken;
    this.token = token;
    this.allowDefaultToken = allowDefaultToken;
  }

  @GetMapping("/internal/executor/tenants/{tenantId}/data-store")
  public ResponseEntity<ExecutorDataStoreDescriptor> describe(
      @RequestHeader(value = "Authorization", required = false) String authHeader,
      @PathVariable long tenantId,
      @RequestParam(value = "include", required = false) String include) {
    // 설정 토큰이 둘 다 비면 아무 요청도 인증할 수 없으므로 모든 요청을 503 으로 거부한다.
    if (executorToken.isBlank() && token.isBlank()) {
      log.error("executor 내부 토큰이 비어 있어 저장소 기술자 제공을 거부한다");
      throw tokenNotConfigured("executor 내부 토큰이 설정되지 않았습니다");
    }
    if (InternalTokens.matches(authHeader, executorToken)) {
      // Python 실행기: 종류만. include=ro 도 무시한다(비밀을 낼 수 없는 토큰이다).
      ExecutorDataStoreDescriptor kind = service.describeBackendOnly(tenantId);
      log.info("executor 저장소 종류 제공: tenant={}, backend={}", tenantId, kind.backend());
      return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(kind);
    }
    if (!InternalTokens.matches(authHeader, token)) {
      return unauthorized();
    }
    // 기본 토큰이면 비밀이 없는 PG 기술자만 내고, 비밀번호가 담길 Tibero 는 서비스가 복호화 전에 거부한다.
    Set<String> includes = includes(include);
    ExecutorDataStoreDescriptor descriptor =
        service.describe(
            tenantId,
            secretsAllowed(),
            includes.contains(INCLUDE_RO),
            includes.contains(INCLUDE_SLOTS));
    log.info("executor 저장소 기술자 제공: tenant={}, backend={}", tenantId, descriptor.backend());
    // 평문 비밀번호가 중간 캐시에 남지 않게 한다.
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(descriptor);
  }

  /** db-executor 가 스크립트 토큰 세대만 짧은 주기로 확인한다. 같은 내부 토큰을 요구하고 비밀은 내지 않는다. */
  @GetMapping("/internal/executor/tenants/{tenantId}/script-token-epoch")
  public ResponseEntity<Map<String, Long>> scriptTokenEpoch(
      @RequestHeader(value = "Authorization", required = false) String authHeader,
      @PathVariable long tenantId) {
    if (token.isBlank()) {
      throw tokenNotConfigured("db-executor 내부 토큰이 설정되지 않았습니다");
    }
    if (!InternalTokens.matches(authHeader, token)) {
      return unauthorized();
    }
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(Map.of("scriptTokenEpoch", service.scriptTokenEpoch(tenantId)));
  }

  /**
   * 설정 토큰이 비어 인증할 수 없을 때의 503 — 두 창구가 같은 코드로 거부한다. 「비었다」의 기준은 창구마다 다르다(기술자: 두 토큰 모두, 세대:
   * db-executor 토큰)라 판정은 호출부에 두고 응답 모양만 여기서 만든다.
   */
  private static CodedApiException tokenNotConfigured(String message) {
    return new CodedApiException(
        HttpStatus.SERVICE_UNAVAILABLE, TOKEN_NOT_CONFIGURED_CODE, message);
  }

  /** 토큰 불일치 401(본문 없음) — 두 창구 공용. */
  private static <T> ResponseEntity<T> unauthorized() {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
  }

  /**
   * {@code include} 를 쉼표로 나눈 집합. 값마다 정확히(대소문자·공백 포함) {@link #KNOWN_INCLUDES} 중 하나여야 하고, 모르는 값이 하나라도
   * 섞이면 빈 집합이다 — 오타·변형이 비밀을 더 내게 하지 않는다(fail-closed, 기존 {@code ro} 정확 일치 규칙의 확장).
   */
  static Set<String> includes(String include) {
    if (include == null || include.isEmpty()) {
      return Set.of();
    }
    String[] parts = include.split(",", -1);
    if (!Arrays.stream(parts).allMatch(KNOWN_INCLUDES::contains)) {
      return Set.of();
    }
    return Set.copyOf(Arrays.asList(parts));
  }

  /**
   * 평문 비밀번호를 내도 되는가(db-executor 토큰 기준): 토큰이 알려진 기본값·{@code change-me} 자리표시자가 아니거나, 허용 플래그(로컬·테스트)가
   * 켜져 있어야 한다.
   */
  private boolean secretsAllowed() {
    return allowDefaultToken
        || !(INSECURE_DEFAULT_TOKENS.contains(token) || token.startsWith(PLACEHOLDER_PREFIX));
  }
}
