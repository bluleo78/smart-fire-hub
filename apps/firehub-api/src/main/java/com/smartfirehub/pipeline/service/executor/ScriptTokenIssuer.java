package com.smartfirehub.pipeline.service.executor;

import com.smartfirehub.global.tenant.TenantPipelineRole;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 스크립트 1회용 토큰 발급기(WD-78, 스펙 §3.4·§5.2, Ruling 16).
 *
 * <p>Tibero 테넌트의 Python 스크립트는 샌드박스에 Tibero 드라이버가 없어 db-executor {@code /execute/query}(readSlot +
 * stream) 로 입력 테이블을 읽는다. 그때 쓰는 자격이 이 토큰이다 — 테넌트·슬롯·세대·만료를 api 가 서명하고 db-executor 가 같은 키로 검증한다.
 * 스크립트(사용자 코드)가 손에 쥐는 값이라 범위를 좁힌다: db-executor 는 이 토큰으로 슬롯 스트리밍 조회만 허용하고, 본문이 아니라 토큰의 테넌트·슬롯을 쓴다.
 *
 * <p>형식: {@code v1.<tenantId>.<slot>.<epoch>.<expEpochSeconds>.<base64url(HMAC-SHA256(key,
 * "v1.<tenantId>.<slot>.<epoch>.<exp>"))>}(패딩 없음). 고정 벡터 {@code script-token-vectors.json} 을
 * db-executor 와 같이 읽어 형식이 갈라지지 않게 한다.
 *
 * <p>{@code epoch} 는 {@code tenant_data_store.script_token_epoch}({@code
 * TenantDataStoreRepository#findScriptTokenEpoch})의 발급 시점 값이다. 등급 정의가 바뀌면 GRANT 를 넓히기 전에 세대를 올리므로
 * db-executor 가 옛 세대 토큰을 거부한다 — 실행 중인 스크립트가 넓어진 권한으로 계속 읽지 못하게(PG 의 슬롯 세션 종료와 같은 효과).
 *
 * <p>키({@code app.db-executor.script-token-key})가 비면 기동은 막지 않되(PG 배포에는 키가 없다) 발급 시점에 실패한다 — 빈 키로
 * 서명한 토큰은 누구나 만들 수 있기 때문이다(fail-closed). 키·토큰은 로그하지 않는다.
 */
@Component
public class ScriptTokenIssuer {

  private static final String VERSION = "v1";

  /**
   * 서명 키 최소 길이(UTF-8 바이트) — db-executor {@code ScriptToken.MIN_KEY_BYTES} 와 같다. 짧은 키는 추측·전수 대입에
   * 약하다.
   */
  static final int MIN_KEY_BYTES = 32;

  private final String key;
  private final Clock clock;

  @Autowired
  public ScriptTokenIssuer(@Value("${app.db-executor.script-token-key:}") String key) {
    this(key, Clock.systemUTC());
  }

  /** 테스트용: 시계를 주입해 고정 벡터의 만료 시각을 재현한다. */
  ScriptTokenIssuer(String key, Clock clock) {
    this.key = key;
    this.clock = clock;
  }

  /**
   * 토큰을 발급한다.
   *
   * @param tenantId 테넌트(1 이상)
   * @param slot 실행 주체의 읽기 슬롯(1~10, {@code PythonReadGrantSync.prepareForRun} 결과)
   * @param epoch 발급 시점의 테넌트 스크립트 토큰 세대(0 이상)
   * @param ttl 유효 시간 — 단계 시간 제한 + 여유(5분). 1초 이상
   * @return {@code Authorization: Script } 뒤에 붙일 토큰
   * @throws IllegalStateException 서명 키가 설정되지 않았거나 {@link #MIN_KEY_BYTES} 바이트보다 짧을 때
   * @throws IllegalArgumentException 인자가 범위를 벗어났을 때
   */
  public String issue(long tenantId, int slot, long epoch, Duration ttl) {
    if (key == null || key.isBlank()) {
      throw new IllegalStateException(
          "app.db-executor.script-token-key(DB_EXECUTOR_SCRIPT_TOKEN_KEY)가 설정되지 않아 스크립트 토큰을 발급할 수 없습니다.");
    }
    if (key.getBytes(StandardCharsets.UTF_8).length < MIN_KEY_BYTES) {
      // 검증기(db-executor)도 같은 길이 미만이면 모든 토큰을 거부한다 — 발급해도 쓸 수 없고, 짧은 키 서명은 약하다(fail-closed).
      throw new IllegalStateException(
          "app.db-executor.script-token-key(DB_EXECUTOR_SCRIPT_TOKEN_KEY)는 "
              + MIN_KEY_BYTES
              + "바이트 이상이어야 합니다.");
    }
    if (tenantId <= 0) {
      throw new IllegalArgumentException("tenantId 는 1 이상이어야 합니다: " + tenantId);
    }
    if (slot < 1 || slot > TenantPipelineRole.PYTHON_READ_SLOTS) {
      throw new IllegalArgumentException(
          "slot 은 1~" + TenantPipelineRole.PYTHON_READ_SLOTS + " 이어야 합니다: " + slot);
    }
    if (epoch < 0) {
      throw new IllegalArgumentException("epoch 는 0 이상이어야 합니다: " + epoch);
    }
    if (ttl == null || ttl.toSeconds() < 1) {
      throw new IllegalArgumentException("ttl 은 1초 이상이어야 합니다: " + ttl);
    }
    long exp = clock.instant().getEpochSecond() + ttl.toSeconds();
    String signed = VERSION + "." + tenantId + "." + slot + "." + epoch + "." + exp;
    return signed + "." + sign(signed);
  }

  /**
   * {@code base64url(HMAC-SHA256(key, message))}, 패딩 없음 — db-executor {@code ScriptToken} 과 같은 계산.
   */
  private String sign(String message) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HMAC-SHA256 을 쓸 수 없습니다", e);
    }
  }

  /** 키를 로그·예외로 흘리지 않는다. */
  @Override
  public String toString() {
    return "ScriptTokenIssuer[key=****]";
  }
}
