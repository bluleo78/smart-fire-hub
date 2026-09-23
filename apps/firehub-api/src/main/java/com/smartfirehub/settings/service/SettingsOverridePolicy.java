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
    /**
     * 테넌트 값 → 코드 기본값. 플랫폼 행은 읽지도 쓰지도 않는다. 코드 기본값이 있는 키는 AI 동작
     * 6키({@link AiBehaviorDefaults})뿐이고, 기본값이 없는 키(SMTP 6키·옛 {@code ai.*})는 테넌트
     * 값이 없으면 미설정(빈 값)이다.
     */
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
   * 테넌트 전용 네임스페이스. {@code ai.*}(#706)와 {@code smtp.*}(#712)는 전부 워크스페이스 소유라
   * 플랫폼 평면이 없다 — 목록에 없는 키도 {@link Plane#TENANT_ONLY} 로 분류해 플랫폼 행을 읽지 않는다
   * (닫힌 쪽 기본값. 옛 평면 3키 {@code ai.agent_type} 등의 행은 V129 가 지웠다).
   *
   * <p><b>SMTP 를 위해 새 평면을 만들지 않은 이유(#712).</b> SMTP 는 "테넌트 값만, 코드 기본값
   * 없음"이다. {@link Plane#TENANT_ONLY} 의 해석(테넌트 값 → 코드 기본값)은 기본값이 없는 키에서
   * 그대로 "미설정"이 되므로 추가 분기가 필요 없다 — 평면을 하나 더 두면 {@code SettingsService}
   * 의 읽기·쓰기 switch 마다 같은 동작의 가지가 하나씩 늘 뿐이다. 이 목록 한 줄이 SMTP 를 플랫폼
   * 조회({@link #mayHavePlatformRows})·플랫폼 쓰기·플랫폼 목록에서 동시에 빼낸다.
   */
  private static final Set<String> TENANT_NAMESPACES = Set.of("ai.", "smtp.");

  /** 플랫폼 잠금 키. 모델 변경이 벡터 차원을 바꾸므로 Phase B 가 차원별 컬럼을 넣을 때까지 플랫폼이 갖는다. */
  private static final Set<String> PLATFORM_ONLY =
      Set.of("embedding.provider", "embedding.model", "embedding.base_url", "embedding.api_key");

  /**
   * SMTP 6키(테넌트 전용, #712). 워크스페이스가 자기 메일 서버를 등록한다. 등록하지 않은 워크스페이스는
   * 미설정이고 발송이 명확한 오류로 실패한다 — 플랫폼 공용 서버로 폴백하지 않는다(사용자 결정
   * 2026-09-23). 워크스페이스 "설정 해제"는 이 6키를 한 묶음으로 지운다.
   */
  private static final Set<String> SMTP_KEYS =
      Set.of(
          "smtp.host",
          "smtp.port",
          "smtp.username",
          "smtp.password",
          "smtp.starttls",
          "smtp.from_address");

  /** 테넌트가 쓸 수 있는 키 전체 = SMTP 6키 ∪ AI 동작 6키. */
  private static final Set<String> TENANT_WRITABLE;

  static {
    Set<String> all = new HashSet<>(SMTP_KEYS);
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
    if (PLATFORM_ONLY.contains(key)) return Plane.PLATFORM_ONLY;
    if (isInTenantNamespace(key)) return Plane.TENANT_ONLY;
    return Plane.UNKNOWN;
  }

  /**
   * 이 프리픽스({@code prefix + "."})에 플랫폼 행이 있을 수 있는가. {@code "ai"}·{@code "smtp"} 처럼
   * 테넌트 네임스페이스 안이면 거짓이라 호출부가 {@code system_settings} 조회를 생략한다.
   */
  public static boolean mayHavePlatformRows(String prefix) {
    return !isInTenantNamespace(prefix + ".");
  }

  /** 키(또는 {@code prefix + "."})가 테넌트 전용 네임스페이스 안인가. */
  private static boolean isInTenantNamespace(String keyOrPrefix) {
    return TENANT_NAMESPACES.stream().anyMatch(keyOrPrefix::startsWith);
  }

  /**
   * 이 키를 테넌트가 자기 값으로 저장할 수 있는가(SMTP 6키 + AI 동작 6키). 옛 {@code ai.*} 키는
   * {@link Plane#TENANT_ONLY} 로 분류되지만 여기서는 거짓이다.
   */
  public static boolean isTenantOverridable(String key) {
    return key != null && TENANT_WRITABLE.contains(key);
  }

  /** 테넌트가 저장할 수 있는 키 전체. */
  public static Set<String> tenantOverridableKeys() {
    return TENANT_WRITABLE;
  }

  /** SMTP 6키. 워크스페이스 "설정 해제"({@code SettingsService#clearSmtpSettings})가 지우는 묶음이다. */
  public static Set<String> smtpKeys() {
    return SMTP_KEYS;
  }

  /** 플랫폼 잠금 키({@code embedding.*} 4키). */
  public static Set<String> platformOnlyKeys() {
    return PLATFORM_ONLY;
  }
}
