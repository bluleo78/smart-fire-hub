package com.smartfirehub.pipeline.service.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.settings.model.AiCredential;
import com.smartfirehub.settings.service.AiCredentialService;
import com.smartfirehub.settings.service.AiCredentialService.ClassifyBinding;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link AiClassifyTargetResolver} 단위 테스트(#707). 분류 전용이면 그 묶음을, 아니면 {@code UseChat}
 * 표식을 준다 — 미설정 경로는 채팅 자격증명을 <b>읽지 않는다</b>(현행과 바이트 동일, 캐시 전량 히트 실행이
 * 깨진 채팅 자격증명 때문에 실패하면 안 된다).
 */
class AiClassifyTargetResolverTest {

  private AiCredentialService aiCredentialService;
  private AiClassifyTargetResolver resolver;

  @BeforeEach
  void setUp() {
    aiCredentialService = mock(AiCredentialService.class);
    resolver = new AiClassifyTargetResolver(aiCredentialService);
  }

  @Test
  void 미설정이면_UseChat_이고_채팅_자격증명은_읽지_않는다() {
    when(aiCredentialService.resolveClassify()).thenReturn(Optional.empty());

    AiClassifyTarget target = resolver.resolve();

    assertThat(target).isSameAs(AiClassifyTarget.USE_CHAT);
    assertThat(target.cacheDiscriminator()).isEmpty();
    verify(aiCredentialService, never()).resolve();
  }

  @Test
  void 설정되면_분류_묶음을_Dedicated_로_준다() {
    AiCredential classify = new AiCredential.CliApi("sk-classify");
    when(aiCredentialService.resolveClassify())
        .thenReturn(Optional.of(new ClassifyBinding(classify, "claude-haiku-4-5")));

    AiClassifyTarget target = resolver.resolve();

    assertThat(target).isEqualTo(new AiClassifyTarget.Dedicated(classify, "claude-haiku-4-5"));
    verify(aiCredentialService, never()).resolve();
  }

  @Test
  void 묶음이_손상되면_채팅으로_폴백하지_않고_예외가_그대로_전파된다() {
    when(aiCredentialService.resolveClassify()).thenThrow(new IllegalStateException("분류 모델이 없습니다"));

    assertThatThrownBy(() -> resolver.resolve()).isInstanceOf(IllegalStateException.class);
    verify(aiCredentialService, never()).resolve();
  }

  @Test
  void 캐시_판별자는_유형_공급자_baseUrl_모델만_담고_비밀은_담지_않는다() {
    AiClassifyTarget opencode =
        new AiClassifyTarget.Dedicated(
            new AiCredential.Opencode("openai", "https://gw.example/v1", "high", "sk-SECRET"),
            "openai/gpt-4o-mini");
    assertThat(opencode.cacheDiscriminator())
        .contains("opencode|openai|https://gw.example/v1|openai/gpt-4o-mini");

    AiClassifyTarget sdk =
        new AiClassifyTarget.Dedicated(new AiCredential.Sdk("oat-SECRET", "sk-SECRET"), "claude-haiku-4-5");
    assertThat(sdk.cacheDiscriminator()).contains("sdk|||claude-haiku-4-5");

    for (AiClassifyTarget t : new AiClassifyTarget[] {opencode, sdk}) {
      assertThat(t.cacheDiscriminator().orElseThrow()).doesNotContain("SECRET");
      assertThat(t.toString()).doesNotContain("SECRET");
    }
  }
}
