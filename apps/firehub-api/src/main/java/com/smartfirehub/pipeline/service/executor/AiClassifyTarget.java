package com.smartfirehub.pipeline.service.executor;

import com.smartfirehub.settings.model.AiCredential;
import java.util.Optional;

/**
 * AI_CLASSIFY 한 번의 스텝 실행이 쓸 공급자(#707). 모양은 둘뿐이다.
 *
 * <ul>
 *   <li>{@link Dedicated} — 분류 전용 묶음. 실행 시작 시 한 번 해석하고, 캐시 해시와 ai-agent 요청
 *       양쪽에 <b>같은 인스턴스</b>를 쓴다(실행 도중 설정이 바뀌어 해시와 요청이 어긋나지 않게).
 *   <li>{@link UseChat} — "채팅 설정을 통째로 쓴다"는 표식. 자격증명을 담지 않는다 — 채팅
 *       자격증명·{@code ai.model} 은 캐시 미스로 요청을 만들 때 {@code AiAgentClient} 가 지금과 똑같이
 *       해석한다. 해시가 채팅 설정에 의존하지 않으므로 여기서 미리 해석할 이유가 없고, 미리 하면
 *       채팅 자격증명이 깨진 테넌트의 캐시 전량 히트 실행까지 실패한다(현행과 달라진다).
 * </ul>
 */
public sealed interface AiClassifyTarget permits AiClassifyTarget.UseChat, AiClassifyTarget.Dedicated {

  /** 상태가 없는 표식이라 하나만 쓴다. */
  AiClassifyTarget USE_CHAT = new UseChat();

  /**
   * 캐시 해시에 섞을 식별자. {@link UseChat} 은 비어 있다 — 해시 입력이 현행과 같아야 기존 캐시가
   * 그대로 히트한다(결정 5). 비밀은 없다.
   */
  Optional<String> cacheDiscriminator();

  /** 채팅(AI 에이전트) 설정 사용 표식. */
  record UseChat() implements AiClassifyTarget {
    @Override
    public Optional<String> cacheDiscriminator() {
      return Optional.empty();
    }
  }

  /** 분류 전용 묶음 — {@code agentType|providerId|baseUrl|model} 로 캐시를 가른다. */
  record Dedicated(AiCredential credential, String model) implements AiClassifyTarget {
    @Override
    public Optional<String> cacheDiscriminator() {
      return Optional.of(credential.cacheIdentity() + "|" + model);
    }

    /** 기본 record toString 은 자격증명 record 의 비밀 필드까지 찍는다 — 로그 유출을 막는다. */
    @Override
    public String toString() {
      return "Dedicated[" + credential.nonSecretSummary(model) + "]";
    }
  }
}
