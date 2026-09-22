package com.smartfirehub.settings.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * AI 동작 설정 6키({@code ai.model}/{@code ai.max_turns}/{@code ai.system_prompt}/
 * {@code ai.temperature}/{@code ai.max_tokens}/{@code ai.session_max_tokens})의 <b>코드 기본값 단일
 * 출처</b>.
 *
 * <p><b>왜 코드에 두는가.</b> AI 설정은 테넌트 전용이다 — 플랫폼({@code system_settings}) 행은
 * 어떤 경로에서도 읽지 않는다(V127 이 남은 {@code ai.%} 플랫폼 행을 지운다). 테넌트가 어떤 키를
 * 저장하지 않았으면 오류가 아니라 이 기본값으로 동작한다(자격증명만은 기본값이 없어 미설정이면
 * 오류로 멈춘다 — {@code AiCredentialService}).
 *
 * <p>값은 옛 플랫폼 시드와 같다: V15(턴 10·온도 1.0·토큰 16384) → V68(모델 claude-sonnet-5) →
 * V69(슬림 시스템 프롬프트). {@code ai.session_max_tokens} 는 어떤 마이그레이션도 시드한 적이 없고
 * 원래부터 코드 폴백(50000)이었다.
 *
 * <p>소비처(채팅 프록시·분류·프로액티브·설정 화면 응답)는 전부 이 클래스를 읽는다 — 호출부마다
 * {@code orElse(...)} 리터럴을 적으면 값이 갈라진다(과거 모델 기본값이 실제로 그렇게 갈렸다).
 */
public final class AiBehaviorDefaults {

  /** 기본 모델. */
  public static final String MODEL = "claude-sonnet-5";

  public static final int MAX_TURNS = 10;

  public static final String SYSTEM_PROMPT =
      "당신은 Smart Fire Hub의 AI 어시스턴트입니다.\n" + "응답은 한국어로 하고, 마크다운 형식을 사용하세요.";

  public static final double TEMPERATURE = 1.0;

  public static final int MAX_TOKENS = 16384;

  public static final int SESSION_MAX_TOKENS = 50000;

  /**
   * 키 → 기본값(문자열). 순서는 설정 화면 응답 순서다. 이 맵의 키 집합이 곧 "테넌트 전용 AI 동작
   * 키" 목록이다 — {@code SettingsOverridePolicy} 가 여기서 가져간다.
   */
  private static final Map<String, String> DEFAULTS;

  static {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("ai.model", MODEL);
    m.put("ai.max_turns", String.valueOf(MAX_TURNS));
    m.put("ai.system_prompt", SYSTEM_PROMPT);
    m.put("ai.temperature", String.valueOf(TEMPERATURE));
    m.put("ai.max_tokens", String.valueOf(MAX_TOKENS));
    m.put("ai.session_max_tokens", String.valueOf(SESSION_MAX_TOKENS));
    DEFAULTS = Collections.unmodifiableMap(m);
  }

  private AiBehaviorDefaults() {}

  /** 키별 기본값(불변, 순서 보존). */
  public static Map<String, String> all() {
    return DEFAULTS;
  }

  /** 테넌트 전용 AI 동작 키 집합. */
  public static Set<String> keys() {
    return DEFAULTS.keySet();
  }

  /** 이 키가 AI 동작 키인지. null 은 false. */
  public static boolean isKey(String key) {
    return key != null && DEFAULTS.containsKey(key);
  }

  /** 키의 기본값. AI 동작 키가 아니면 null. */
  public static String defaultOf(String key) {
    return DEFAULTS.get(key);
  }
}
