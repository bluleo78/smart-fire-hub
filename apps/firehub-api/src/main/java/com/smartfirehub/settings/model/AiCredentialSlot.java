package com.smartfirehub.settings.model;

import java.util.Set;

/**
 * AI 자격증명 문서가 놓이는 자리(슬롯). {@code AiCredentialService} 가 이 enum 으로 어느
 * {@code tenant_settings} 키를 읽고 쓰는지 고른다.
 *
 * <p><b>왜 슬롯인가(#707).</b> 분류(AI_CLASSIFY)만 싼 공급자로 내리려면 채팅과 <b>같은 문서 구조</b>
 * ({v, agentType, payload, secret})를 한 벌 더 가져야 한다. 구조가 같아야 암호화·마스킹·SSRF 가드·
 * probe·{@code AiCredentialSwitchGuardTest} 를 그대로 물려받는다 — 키 문자열만 다르다.
 *
 * <p>분류 모델({@link #CLASSIFY_MODEL_KEY})은 자격증명 문서 밖의 별도 키다 — {@link AiCredential}
 * 은 "모델은 여기 없다"가 설계 불변식이다(채팅도 {@code ai.model} 이 별도 키).
 */
public enum AiCredentialSlot {
  /** AI 에이전트(채팅·프로액티브·GraphRAG) 자격증명. */
  CHAT("ai.credential"),
  /** AI_CLASSIFY 전용 자격증명. 행이 없으면 분류는 {@link #CHAT} 설정을 통째로 쓴다. */
  CLASSIFY("ai.classify_credential");

  /** 분류 전용 모델 키. {@link #CLASSIFY} 행과 반드시 함께 쓰이고 함께 지워진다. */
  public static final String CLASSIFY_MODEL_KEY = "ai.classify_model";

  private final String key;

  AiCredentialSlot(String key) {
    this.key = key;
  }

  /** 이 슬롯의 {@code tenant_settings} 키. */
  public String key() {
    return key;
  }

  /**
   * 이 enum 이 소유하는(= {@code AiCredentialService} 만 읽고 쓰는) 키 전체. 설정 평면 정책이 이
   * 집합을 전용 서비스 소유로 분류해 범용 설정 경로에서 막는다.
   */
  public static Set<String> ownedKeys() {
    return Set.of(CHAT.key, CLASSIFY.key, CLASSIFY_MODEL_KEY);
  }
}
