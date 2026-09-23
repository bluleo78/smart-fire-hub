package com.smartfirehub.pipeline.service.executor;

import com.smartfirehub.settings.model.AiCredential;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * AI_CLASSIFY 한 번의 스텝 실행이 쓸 공급자(#707). 모양은 둘뿐이다.
 *
 * <ul>
 *   <li>{@link Dedicated} — 분류 전용 묶음. 실행 시작 시 한 번 해석하고, 캐시 해시와 ai-agent 요청
 *       양쪽에 <b>같은 인스턴스</b>를 쓴다(실행 도중 설정이 바뀌어 해시와 요청이 어긋나지 않게).
 *   <li>{@link UseChat} — "채팅 설정을 통째로 쓴다"는 표식. 실행 시작 시점에는 자격증명을 담지 않는다
 *       — 채팅 자격증명·{@code ai.model} 은 첫 캐시 미스 배치가 요청을 만들 때 {@code AiAgentClient}
 *       가 해석한다. 해시가 채팅 설정에 의존하지 않으므로 미리 해석할 이유가 없고, 미리 하면 채팅
 *       자격증명이 깨진 테넌트의 캐시 전량 히트 실행까지 실패한다(현행과 달라진다).
 * </ul>
 */
public sealed interface AiClassifyTarget permits AiClassifyTarget.UseChat, AiClassifyTarget.Dedicated {

  /**
   * 캐시 해시에 섞을 식별자. {@link UseChat} 은 비어 있다 — 해시 입력이 현행과 같아야 기존 캐시가
   * 그대로 히트한다(결정 5). 비밀은 없다.
   */
  Optional<String> cacheDiscriminator();

  /**
   * 채팅(AI 에이전트) 설정 사용 표식 — <b>실행 하나당 새 인스턴스</b>다(#707 후속).
   *
   * <p>예전에는 상태 없는 싱글턴이었고, 그래서 캐시 미스 배치마다 채팅 자격증명을 다시 읽고
   * 복호화했다. 이제 첫 번째로 <b>성공한</b> 해석 결과를 이 인스턴스에 기억해 같은 실행의 다음
   * 배치가 재사용한다.
   *
   * <ul>
   *   <li><b>왜 싱글턴을 없앴나</b>: 기억 칸이 공유되면 여러 테넌트의 파이프라인이 동시에 돌 때 한
   *       테넌트의 복호화된 채팅 자격증명이 다른 테넌트의 요청 바디에 실린다. 인스턴스를 실행마다 새로
   *       만들면({@code AiClassifyTargetResolver}) 그 경로가 구조적으로 없다.
   *   <li><b>실패는 기억하지 않는다</b>: 해석이나 검증이 던지면 칸은 비어 있는 채로 남고 다음 배치가
   *       다시 해석한다 — 오늘의 배치별 onError 동작이 그대로다. 반대로 한 번 해석에 성공하면 그
   *       자격증명을 실행이 끝날 때까지 재사용하므로, 실행 도중 키를 바꾸거나 폐기해도 반영은 다음
   *       실행부터다.
   *   <li><b>동기화가 없는 이유</b>: 한 실행의 배치 루프(RETRY_BATCH 재시도 포함)는 한 스레드에서
   *       순차로 돈다. 인스턴스가 실행 밖으로 새지 않으므로 동시 접근이 없다.
   * </ul>
   *
   * <p>해시에는 영향이 없다 — {@link #cacheDiscriminator()} 는 여전히 비어 있다.
   */
  final class UseChat implements AiClassifyTarget {

    /** 첫 성공 해석 결과. 비밀을 담으므로 toString 에 찍지 않는다. */
    private ResolvedBinding remembered;

    @Override
    public Optional<String> cacheDiscriminator() {
      return Optional.empty();
    }

    /**
     * 기억한 해석이 있으면 돌려주고, 없으면 {@code resolver} 로 해석해 <b>성공했을 때만</b> 기억한다.
     * {@code resolver} 가 던지면 그대로 전파되고 칸은 비어 있다(다음 호출이 다시 해석한다).
     */
    public ResolvedBinding resolveOnce(Supplier<ResolvedBinding> resolver) {
      if (remembered == null) {
        remembered = resolver.get();
      }
      return remembered;
    }

    /** 기억한 자격증명(비밀)을 로그에 흘리지 않는다. */
    @Override
    public String toString() {
      return "UseChat[" + (remembered == null ? "unresolved" : "resolved") + "]";
    }
  }

  /**
   * 해석·검증을 통과한 모델 + 완전한 자격증명 묶음. {@code AiAgentClient} 가 두 모양 모두에서 이것으로
   * 바디를 만들고, {@link UseChat} 은 채팅 쪽 결과를 실행 동안 기억한다.
   * 기본 record toString 은 자격증명의 비밀 필드까지 찍으므로 비밀 없는 요약만 찍는다.
   */
  record ResolvedBinding(String model, AiCredential credential) {
    @Override
    public String toString() {
      return "ResolvedBinding[" + credential.nonSecretSummary(model) + "]";
    }
  }

  /**
   * 분류 전용 묶음 — {@code agentType|providerId|baseUrl[|reasoningEffort]|model} 로 캐시를 가른다.
   * 모델 칸도 자격증명 칸과 같은 규칙({@link AiCredential#cacheIdentityPart})으로 인코딩해 칸 경계가
   * 값 안의 {@code |} 로 흔들리지 않게 한다(#707 후속).
   */
  record Dedicated(AiCredential credential, String model) implements AiClassifyTarget {
    @Override
    public Optional<String> cacheDiscriminator() {
      return Optional.of(credential.cacheIdentity() + "|" + AiCredential.cacheIdentityPart(model));
    }

    /** 기본 record toString 은 자격증명 record 의 비밀 필드까지 찍는다 — 로그 유출을 막는다. */
    @Override
    public String toString() {
      return "Dedicated[" + credential.nonSecretSummary(model) + "]";
    }
  }
}
