package com.smartfirehub.embedding.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.settings.service.OpencodeProbeService;
import com.smartfirehub.settings.service.OpencodeProbeService.ProbeResult;
import com.smartfirehub.settings.service.OpencodeProbeService.TargetCheck;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Base URL 가드: 형식 → (Ollama 허용 목록) → SSRF 가드(OpencodeProbeService 재사용).
 *
 * <p>TargetCheck 는 private 생성자의 public 비-final 클래스라 목(mock)으로 ok/failure 를 흉내 낸다 — DNS·사설 대역 판정 자체의
 * 정확성은 OpencodeProbeServiceTest 가 이미 지킨다.
 */
class EmbeddingTargetGuardTest {

  private final OpencodeProbeService probe = mock(OpencodeProbeService.class);
  private final EmbeddingTargetGuard guard =
      new EmbeddingTargetGuard(probe, "http://host.docker.internal:11434, http://localhost:11434/");

  private static TargetCheck ok() {
    TargetCheck c = mock(TargetCheck.class);
    when(c.ok()).thenReturn(true);
    return c;
  }

  private static TargetCheck blocked(String message) {
    TargetCheck c = mock(TargetCheck.class);
    when(c.ok()).thenReturn(false);
    when(c.failure())
        .thenReturn(new ProbeResult(false, List.of(), message, ProbeResult.Reason.BLOCKED_ADDRESS));
    return c;
  }

  @Test
  void malformedUrlIsRejected() {
    assertThatThrownBy(() -> guard.check(EmbeddingProviderType.OLLAMA, "ftp://x"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("http:// 또는 https://");
  }

  @Test
  void allowListedOllamaSkipsSsrfGuard() {
    // 끝 슬래시 차이는 정규화로 흡수한다.
    assertThatCode(() -> guard.check(EmbeddingProviderType.OLLAMA, "http://localhost:11434"))
        .doesNotThrowAnyException();
    verify(probe, never()).validateTargetOnly(anyString());
  }

  @Test
  void nonAllowListedPrivateOllamaIsRejected() {
    // blocked(...) 는 내부에서 새 mock 을 만들고 when() 을 쓴다 — 바깥 when(...) 인자로 바로 넣으면
    // Mockito 가 "완료되지 않은 스터빙"으로 오해해 UnfinishedStubbingException 을 던진다. 먼저 값을
    // 로컬 변수로 확정한 뒤에만 바깥 when() 을 호출한다.
    TargetCheck failure = blocked("https 만 허용됩니다");
    when(probe.validateTargetOnly("http://10.0.0.5:11434")).thenReturn(failure);
    assertThatThrownBy(() -> guard.check(EmbeddingProviderType.OLLAMA, "http://10.0.0.5:11434"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("운영자가 허용한 주소");
  }

  @Test
  void openAiRequiresHttps() {
    assertThatThrownBy(() -> guard.check(EmbeddingProviderType.OPENAI, "http://api.openai.com"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("https");
  }

  @Test
  void openAiAllowListDoesNotApply() {
    // 허용 목록은 Ollama 전용이다 — OpenAI 는 항상 전체 가드를 지난다.
    assertThatThrownBy(
            () -> guard.check(EmbeddingProviderType.OPENAI, "http://host.docker.internal:11434"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void openAiPublicHttpsPassesGuard() {
    TargetCheck passed = ok();
    when(probe.validateTargetOnly("https://api.openai.com")).thenReturn(passed);
    assertThatCode(() -> guard.check(EmbeddingProviderType.OPENAI, "https://api.openai.com"))
        .doesNotThrowAnyException();
  }

  @Test
  void openAiBlockedAddressIsRejectedWithGuardMessage() {
    TargetCheck failure = blocked("허용되지 않은 대상 주소입니다");
    when(probe.validateTargetOnly("https://internal.example")).thenReturn(failure);
    assertThatThrownBy(() -> guard.check(EmbeddingProviderType.OPENAI, "https://internal.example"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("허용되지 않은 대상 주소입니다");
  }
}
