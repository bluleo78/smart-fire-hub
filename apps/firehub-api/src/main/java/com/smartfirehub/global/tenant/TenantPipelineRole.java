package com.smartfirehub.global.tenant;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 테넌트별 파이프라인 실행 DB 롤({@code pipeline_executor_t{tenantId}})의 <b>이름·비밀번호를
 * 조립하는 유일한 지점</b>. {@link DataSchema} 가 스키마명을 한 점으로 좁힌 것과 같은 이유로,
 * 롤 이름·비밀번호 조립도 흩어지면 P3-b2(리네임 밴드)에서 손댈 곳이 다시 늘어난다.
 *
 * <p><b>호출부는 롤 이름·비밀번호를 문자열로 직접 이어 붙이지 않는다.</b> 반드시 {@link #roleName}
 * / {@link #password} 를 거친다 — 이 규약은 {@code TenantPipelineRoleTest} 가 고정한다.
 *
 * <p><b>왜 비밀번호를 저장하지 않고 매번 파생하는가.</b> 테넌트 생성은 (R6 에 따라) 운영자가 실행하는
 * SQL 스크립트이지 애플리케이션 API 가 아니다. 만약 비밀번호를 애플리케이션 저장소(DB·설정)에
 * 별도로 저장한다면, "운영자가 DB 롤을 만들 때 정하는 값"과 "앱이 접속할 때 읽는 값" 이라는 두 개의
 * 동기화 지점이 생기고, 둘이 어긋나면 인증 실패로만 드러나 원인 추적이 어렵다. 결정적 파생(HMAC)을
 * 쓰면 두 지점이 같은 함수로 같은 값을 계산하므로 동기화 지점이 애초에 존재하지 않는다.
 * {@code RolePasswordSyncCallback}(T2) 이 Flyway 콜백에서 이 값으로 {@code ALTER ROLE ... PASSWORD}
 * 를 실행해 DB 쪽 비밀번호를 앱 기동 시마다 동일한 값으로 맞춘다.
 *
 * <p><b>P3-b2 에서 무엇이 바뀌는가 — 아무것도.</b> 이 클래스의 조립 규약은 물리 스키마가
 * {@code data_t{tenantId}} 로 개명되는 것과 무관하게 그대로 유지된다. P3-b2 가 바꾸는 것은
 * {@link DataSchema#current()} 의 반환값 한 줄뿐이다.
 */
public final class TenantPipelineRole {

  /** HMAC 다이제스트를 hex 로 표기했을 때 비밀번호로 잘라 쓰는 길이(문자 수). */
  private static final int PASSWORD_LENGTH = 32;

  private static final String HMAC_ALGORITHM = "HmacSHA256";

  private TenantPipelineRole() {}

  /**
   * 테넌트 {@code tenantId} 의 파이프라인 실행 롤 이름을 돌려준다 — {@code pipeline_executor_t1} 형태.
   *
   * <p>0 이하 테넌트 id 는 거부한다. 하이픈(음수 부호)은 인용 없는 PostgreSQL 식별자에서 허용되지
   * 않는 문자라, 음수를 그대로 이어 붙이면 문법 오류이거나 (더 나쁘게는) 이름이 잘려 다른 롤과
   * 충돌하는 식별자가 만들어질 수 있다. 애초에 테넌트 id 는 1부터 시작하는 시퀀스 값이므로 0 이하는
   * 입력 오류로 fail-fast 한다.
   *
   * @param tenantId 롤을 만들 테넌트의 id
   * @return {@code pipeline_executor_t{tenantId}}
   * @throws IllegalArgumentException tenantId 가 0 이하일 때
   */
  public static String roleName(long tenantId) {
    if (tenantId <= 0) {
      throw new IllegalArgumentException("tenantId 는 양수여야 합니다: " + tenantId);
    }
    return "pipeline_executor_t" + tenantId;
  }

  /**
   * 테넌트 {@code tenantId} 의 파이프라인 실행 롤 비밀번호를 {@code secret} 으로부터 결정적으로
   * 파생한다. 같은 (tenantId, secret) 쌍은 항상 같은 값을, tenantId 나 secret 중 하나만 달라도
   * 다른 값을 낸다 — 테넌트 간 비밀번호가 서로 유추 가능하지 않도록 HMAC-SHA256 을 쓴다(단순 해시
   * 연결은 길이 확장 공격에 노출된다).
   *
   * <p>{@code secret} 을 HMAC 키로, tenantId 문자열을 메시지로 사용한다 — secret 이 유출되지
   * 않는 한 tenantId 를 안다고 해서 다른 테넌트의 비밀번호를 계산할 수 없다.
   *
   * @param tenantId 비밀번호를 파생할 테넌트의 id
   * @param secret HMAC 키로 쓰는 애플리케이션 비밀값({@code app.pipeline.role-password-secret})
   * @return 32자 소문자 hex 문자열
   */
  public static String password(long tenantId, String secret) {
    // 빈 secret 을 거부한다 — 이것이 없으면 prod fail-closed 의도가 무력화된다.
    //
    // 왜 `@Value` 만으로는 부족한가: `application-prod.yml` 의 `${PIPELINE_ROLE_PASSWORD_SECRET}`
    // 은 프로퍼티가 **부재**할 때만 실패한다. `.env` 에 `PIPELINE_ROLE_PASSWORD_SECRET=` 로 빈 값을
    // 두거나 compose 가 미설정 변수를 보간하면 `""` 로 **정상 해석**된다. 그러면 빈 키로 HMAC 이
    // 돌고, RolePasswordSyncCallback 이 그 **누구나 계산 가능한** 비밀번호를 모든 테넌트 롤에
    // `ALTER ROLE ... PASSWORD` 로 실제로 써 버린다.
    //
    // Python 쪽 twin(`app/tenant.py` 의 resolve_password)은 이미 이 경우를 막고 있었다 —
    // 한쪽만 막혀 있던 비대칭을 여기서 맞춘다.
    if (secret == null || secret.isBlank()) {
      throw new IllegalStateException(
          "app.pipeline.role-password-secret 이 비어 있습니다 — 테넌트 롤 비밀번호가 예측 가능해지므로 기동을 중단한다");
    }
    try {
      Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
      byte[] digest = mac.doFinal(Long.toString(tenantId).getBytes(StandardCharsets.UTF_8));
      return toHex(digest).substring(0, PASSWORD_LENGTH);
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      // HmacSHA256 은 모든 JVM 표준 프로바이더에 포함되므로 정상 실행 경로에서는 발생하지 않는다.
      throw new IllegalStateException("HMAC-SHA256 파생 실패", e);
    }
  }

  private static String toHex(byte[] bytes) {
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      sb.append(String.format("%02x", b));
    }
    return sb.toString();
  }
}
