package com.smartfirehub.settings.service;

import com.smartfirehub.settings.model.AiBehaviorDefaults;
import com.smartfirehub.settings.model.AiCredentialSlot;
import java.util.HashSet;
import java.util.Set;

/**
 * 설정 키가 <b>어느 평면에서 해석되는가</b>의 단일 출처(설계서 §4.5). {@link #planeOf} 가 키마다
 * {@link Plane} 을 정하고, {@code SettingsService} 의 읽기·쓰기 경로는 전부 그 값으로 분기한다.
 *
 * <p><b>왜 deny-list 가 아니라 allow-list 인가</b>: 목록에 없는 키는 {@link Plane#UNKNOWN} 이 되어
 * 테넌트 쓰기에도 플랫폼 쓰기에도 열리지 않는다 — 장래에 누가 키를 추가하고 이 파일을 잊어도
 * fail-closed 다.
 */
public final class SettingsOverridePolicy {

  /** 키의 해석 평면. */
  public enum Plane {
    /** 플랫폼만 값을 갖는다({@code embedding.*} — 모델 변경이 기존 임베딩 전량을 무효화한다). */
    PLATFORM_ONLY,
    /** 플랫폼 기본값 + 테넌트 값(SMTP 6키). 테넌트 값이 있으면 그 값이 우선한다. */
    TWO_PLANE,
    /** 테넌트 값 → 코드 기본값({@link AiBehaviorDefaults}). 플랫폼 행은 읽지도 쓰지도 않는다. */
    TENANT_ONLY,
    /**
     * 전용 서비스가 소유하는 값({@link AiCredentialSlot#ownedKeys()} → {@link AiCredentialService}). 범용
     * 경로 금지.
     */
    EXTERNAL_OWNER,
    /** 분류되지 않은 키 — 플랫폼 행이 있으면 읽기만 되고, 어느 평면에서도 쓸 수 없다. */
    UNKNOWN;

    /** {@code system_settings} 행이 이 키의 값으로 쓰일 수 있는가. */
    public boolean readsPlatformRow() {
      return this != TENANT_ONLY && this != EXTERNAL_OWNER;
    }
  }

  /**
   * AI 설정 네임스페이스. {@code ai.*} 는 전부 테넌트 소유라 플랫폼 평면이 없다 — 동작 6키가 아닌
   * 옛 키({@code ai.agent_type} 등)도 {@link Plane#TENANT_ONLY} 로 분류해 플랫폼 행을 읽지 않는다.
   */
  private static final String TENANT_NAMESPACE = "ai.";

  /** 플랫폼 잠금 키. 모델 변경이 벡터 차원을 바꾸므로 Phase B 가 차원별 컬럼을 넣을 때까지 플랫폼이 갖는다. */
  private static final Set<String> PLATFORM_ONLY =
      Set.of("embedding.provider", "embedding.model", "embedding.base_url", "embedding.api_key");

  /**
   * 두 평면 키(SMTP 6키). "자기 조직 명의로 메일을 보낸다"는 테넌트 요구가 있고, 값을 바꿔도 기존
   * 데이터가 무효화되지 않는다. 미설정 테넌트는 플랫폼 기본값으로 폴백한다.
   */
  private static final Set<String> TWO_PLANE =
      Set.of(
          "smtp.host",
          "smtp.port",
          "smtp.username",
          "smtp.password",
          "smtp.starttls",
          "smtp.from_address");

  /** 테넌트가 쓸 수 있는 키 전체 = 두 평면 키 ∪ 테넌트 전용 AI 동작 키. */
  private static final Set<String> TENANT_WRITABLE;

  static {
    Set<String> all = new HashSet<>(TWO_PLANE);
    all.addAll(AiBehaviorDefaults.keys());
    TENANT_WRITABLE = Set.copyOf(all);
  }

  private SettingsOverridePolicy() {}

  /** 키의 해석 평면. null·미등록 키는 {@link Plane#UNKNOWN}. */
  public static Plane planeOf(String key) {
    if (key == null) return Plane.UNKNOWN;
    // 자격증명 슬롯 소유 키(채팅·분류 자격증명 + 분류 모델, #707) — 분류 모델이 아래 "그 밖의 ai.*"
    // 규칙에 떨어지면 범용 경로가 묶음 한쪽만 바꿀 수 있게 된다.
    if (AiCredentialSlot.ownedKeys().contains(key)) return Plane.EXTERNAL_OWNER;
    if (TWO_PLANE.contains(key)) return Plane.TWO_PLANE;
    if (PLATFORM_ONLY.contains(key)) return Plane.PLATFORM_ONLY;
    if (key.startsWith(TENANT_NAMESPACE)) return Plane.TENANT_ONLY;
    return Plane.UNKNOWN;
  }

  /**
   * 이 프리픽스({@code prefix + "."})에 플랫폼 행이 있을 수 있는가. {@code "ai"} 처럼 테넌트
   * 네임스페이스 안이면 거짓이라 호출부가 {@code system_settings} 조회를 생략한다.
   */
  public static boolean mayHavePlatformRows(String prefix) {
    return !(prefix + ".").startsWith(TENANT_NAMESPACE);
  }

  /**
   * 이 키를 테넌트가 자기 값으로 저장할 수 있는가(두 평면 키 + AI 동작 6키). 옛 {@code ai.*} 키는
   * {@link Plane#TENANT_ONLY} 로 분류되지만 여기서는 거짓이다.
   */
  public static boolean isTenantOverridable(String key) {
    return key != null && TENANT_WRITABLE.contains(key);
  }

  /** 테넌트가 저장할 수 있는 키 전체. */
  public static Set<String> tenantOverridableKeys() {
    return TENANT_WRITABLE;
  }

  /** 두 평면 키(SMTP 6키). */
  public static Set<String> twoPlaneKeys() {
    return TWO_PLANE;
  }

  /** 플랫폼 잠금 키({@code embedding.*} 4키). */
  public static Set<String> platformOnlyKeys() {
    return PLATFORM_ONLY;
  }
}
