package com.smartfirehub.embedding.config;

import com.smartfirehub.apiconnection.service.UrlUtils;
import com.smartfirehub.settings.service.OpencodeProbeService;
import com.smartfirehub.settings.service.OpencodeProbeService.TargetCheck;
import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 임베딩 Base URL 저장 가드.
 *
 * <p><b>왜 필요한가.</b> 이전에는 플랫폼 운영자만 임베딩 주소를 정했다. 테넌트 전용으로 바꾸면 {@code ai:settings}
 * 를 가진 테넌트 관리자가 api 서버로 하여금 임의 주소에 POST 하게 만들 수 있다(SSRF). 그래서 AI 자격증명 프로브가
 * 쓰는 가드({@link OpencodeProbeService#validateTargetOnly} — https·443/8443·공인 IP·DNS 해석 검사)를 그대로
 * 재사용한다(판정 로직 복제 금지).
 *
 * <p><b>Ollama 허용 목록.</b> Ollama 는 본질적으로 사설망(예: {@code http://host.docker.internal:11434})에서
 * 돌아 위 가드를 통과할 수 없다. 운영자가 {@code app.embedding.ollama-allowed-base-urls} 로 허용한 주소와
 * <b>정확히</b> 일치할 때만 가드를 건너뛴다. 목록에 없으면 Ollama 도 공개 https 여야 한다.
 */
@Component
public class EmbeddingTargetGuard {

  static final String MSG_URL_FORMAT =
      "임베딩 Base URL 은 http:// 또는 https:// 로 시작하는 올바른 주소여야 합니다";
  static final String MSG_OPENAI_HTTPS = "OpenAI 임베딩 provider 의 Base URL 은 https 주소여야 합니다";
  static final String MSG_OLLAMA_NOT_ALLOWED =
      "Ollama Base URL 은 운영자가 허용한 주소이거나 공개 https 주소여야 합니다: ";

  private final OpencodeProbeService probeService;
  private final Set<String> ollamaAllowed;

  public EmbeddingTargetGuard(
      OpencodeProbeService probeService,
      @Value("${app.embedding.ollama-allowed-base-urls:}") String ollamaAllowedCsv) {
    this.probeService = probeService;
    // 쉼표 구분 목록을 정규화(공백·끝 슬래시 제거)해 정확 일치 비교에 쓴다.
    this.ollamaAllowed =
        Arrays.stream(ollamaAllowedCsv.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .map(UrlUtils::normalizeBaseUrl)
            .collect(Collectors.toUnmodifiableSet());
  }

  /** 통과하지 못하면 400 으로 번역되는 IllegalArgumentException 을 던진다. */
  public void check(EmbeddingProviderType provider, String baseUrl) {
    String trimmed = baseUrl == null ? "" : baseUrl.trim();
    String scheme = scheme(trimmed).orElseThrow(() -> new IllegalArgumentException(MSG_URL_FORMAT));
    String normalized = UrlUtils.normalizeBaseUrl(trimmed);

    if (provider == EmbeddingProviderType.OLLAMA && ollamaAllowed.contains(normalized)) return;
    if (provider == EmbeddingProviderType.OPENAI && !"https".equals(scheme)) {
      throw new IllegalArgumentException(MSG_OPENAI_HTTPS);
    }
    TargetCheck check = probeService.validateTargetOnly(normalized);
    if (!check.ok()) {
      String reason = check.failure().message();
      throw new IllegalArgumentException(
          provider == EmbeddingProviderType.OLLAMA ? MSG_OLLAMA_NOT_ALLOWED + reason : reason);
    }
  }

  /** http/https 절대 URL 의 스킴(소문자). 호스트가 없거나 다른 스킴이면 empty. */
  private static Optional<String> scheme(String url) {
    try {
      URI uri = URI.create(url);
      if (uri.getHost() == null || uri.getScheme() == null) return Optional.empty();
      String s = uri.getScheme().toLowerCase(Locale.ROOT);
      return "http".equals(s) || "https".equals(s) ? Optional.of(s) : Optional.empty();
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
