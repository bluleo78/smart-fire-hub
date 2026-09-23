package com.smartfirehub.pipeline.service.executor;

import com.smartfirehub.settings.service.AiCredentialService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 분류 공급자의 실행 단위 해석기(#707). <b>분류 슬롯만 본다</b> — 분류 전용 묶음이 있으면
 * {@link AiClassifyTarget.Dedicated}, 없으면 {@link AiClassifyTarget#USE_CHAT}.
 *
 * <p>채팅 자격증명을 여기서 읽지 않는 이유는 {@link AiClassifyTarget.UseChat} javadoc 참고(미설정 테넌트는
 * 현행과 바이트 단위로 같아야 한다). 분류 묶음이 손상됐으면 {@code resolveClassify()} 의 예외가 그대로
 * 나간다 — 채팅으로 폴백하지 않는다(신규 기능이라 실행 시작 fail-fast 가 허용된다).
 */
@Component
@RequiredArgsConstructor
public class AiClassifyTargetResolver {

  private final AiCredentialService aiCredentialService;

  public AiClassifyTarget resolve() {
    return aiCredentialService
        .resolveClassify()
        .<AiClassifyTarget>map(b -> new AiClassifyTarget.Dedicated(b.credential(), b.model()))
        .orElse(AiClassifyTarget.USE_CHAT);
  }
}
