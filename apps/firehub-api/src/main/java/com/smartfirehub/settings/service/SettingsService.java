package com.smartfirehub.settings.service;

import com.smartfirehub.apiconnection.service.EncryptionService;
import com.smartfirehub.settings.dto.SettingResponse;
import com.smartfirehub.settings.repository.SettingsRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SettingsService {

  private static final Set<String> ALLOWED_AI_KEYS =
      Set.of(
          "ai.model",
          "ai.max_turns",
          "ai.system_prompt",
          "ai.temperature",
          "ai.max_tokens",
          "ai.session_max_tokens",
          "ai.api_key",
          "ai.agent_type",
          "ai.cli_oauth_token");

  private static final Set<String> ALLOWED_SMTP_KEYS =
      Set.of(
          "smtp.host",
          "smtp.port",
          "smtp.username",
          "smtp.password",
          "smtp.starttls",
          "smtp.from_address");

  // 임베딩 provider 설정 키 (V63 시드). embedding.api_key 는 ai.api_key 와 동일하게 암호화/마스킹 처리한다.
  private static final Set<String> ALLOWED_EMBEDDING_KEYS =
      Set.of("embedding.provider", "embedding.model", "embedding.base_url", "embedding.api_key");

  private final SettingsRepository settingsRepository;
  private final EncryptionService encryptionService;

  @Transactional(readOnly = true)
  public List<SettingResponse> getByPrefix(String prefix) {
    return settingsRepository.findByPrefix(prefix).stream()
        .map(
            setting -> {
              if ("ai.api_key".equals(setting.key())
                  || "ai.cli_oauth_token".equals(setting.key())
                  || "embedding.api_key".equals(setting.key())) {
                String masked =
                    setting.value() == null || setting.value().isBlank()
                        ? ""
                        : encryptionService.maskValue(encryptionService.decrypt(setting.value()));
                return new SettingResponse(
                    setting.key(), masked, setting.description(), setting.updatedAt());
              }
              return setting;
            })
        .collect(Collectors.toList());
  }

  @Transactional(readOnly = true)
  public Optional<String> getValue(String key) {
    return settingsRepository.getValue(key);
  }

  @Transactional(readOnly = true)
  public Map<String, String> getAsMap(String prefix) {
    // system_settings.value 컬럼은 nullable이므로 null value가 있으면 Collectors.toMap이 NPE를 발생시킨다.
    // null value는 빈 문자열로 대체하고, 중복 키 발생 시 나중 값(b)을 사용하는 merge function을 지정한다.
    return settingsRepository.findByPrefix(prefix).stream()
        .collect(
            Collectors.toMap(
                SettingResponse::key, s -> s.value() != null ? s.value() : "", (a, b) -> b));
  }

  public void updateSettings(Map<String, String> settings, Long userId) {
    for (String key : settings.keySet()) {
      if (!ALLOWED_AI_KEYS.contains(key) && !ALLOWED_EMBEDDING_KEYS.contains(key)) {
        throw new IllegalArgumentException("허용되지 않는 설정 키: " + key);
      }
    }

    boolean hasMaskedApiKey = isMaskedApiKey(settings.get("ai.api_key"));
    boolean hasMaskedCliToken = isMaskedApiKey(settings.get("ai.cli_oauth_token"));
    boolean hasMaskedEmbeddingKey = isMaskedApiKey(settings.get("embedding.api_key"));

    // Skip validation and save for masked values (unchanged by user)
    Map<String, String> filtered =
        settings.entrySet().stream()
            .filter(e -> !(hasMaskedApiKey && "ai.api_key".equals(e.getKey())))
            .filter(e -> !(hasMaskedCliToken && "ai.cli_oauth_token".equals(e.getKey())))
            .filter(e -> !(hasMaskedEmbeddingKey && "embedding.api_key".equals(e.getKey())))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    validateValues(filtered);
    validateEmbeddingConsistency(filtered);

    Map<String, String> toUpdate =
        filtered.entrySet().stream()
            .collect(
                Collectors.toMap(Map.Entry::getKey, e -> encryptIfSecret(e.getKey(), e.getValue())));

    if (!toUpdate.isEmpty()) {
      settingsRepository.updateSettings(toUpdate, userId);
    }
  }

  private static boolean isMaskedApiKey(String value) {
    return value != null && value.startsWith("****");
  }

  /**
   * 비밀 값(ai.api_key, ai.cli_oauth_token, embedding.api_key)은 저장 전 암호화한다. embedding.api_key 는 Ollama
   * 로컬 등 키가 불필요한 경우 빈 문자열일 수 있으므로, 빈 값은 암호화하지 않고 그대로 둔다(빈 ciphertext 복호화 실패 방지).
   */
  private String encryptIfSecret(String key, String value) {
    if ("ai.api_key".equals(key) || "ai.cli_oauth_token".equals(key)) {
      return encryptionService.encrypt(value);
    }
    if ("embedding.api_key".equals(key)) {
      return value.isBlank() ? value : encryptionService.encrypt(value);
    }
    return value;
  }

  @Transactional(readOnly = true)
  public Optional<String> getDecryptedApiKey() {
    return getValue("ai.api_key").filter(v -> !v.isBlank()).map(encryptionService::decrypt);
  }

  @Transactional(readOnly = true)
  public Optional<String> getDecryptedCliOauthToken() {
    return getValue("ai.cli_oauth_token").filter(v -> !v.isBlank()).map(encryptionService::decrypt);
  }

  /**
   * 임베딩 provider 인증용 복호화된 API 키. OpenAI 등 인증이 필요한 provider 에서만 사용하며, Ollama(로컬)는 빈 값이라
   * empty 를 반환한다. 키는 절대 ai-agent 로 내려보내지 않고 api 내부(EmbeddingProviderFactory)에서만 쓴다.
   */
  @Transactional(readOnly = true)
  public Optional<String> getDecryptedEmbeddingApiKey() {
    return getValue("embedding.api_key").filter(v -> !v.isBlank()).map(encryptionService::decrypt);
  }

  @Transactional(readOnly = true)
  public List<SettingResponse> getSmtpSettings() {
    return settingsRepository.findByPrefix("smtp").stream()
        .map(
            setting -> {
              if ("smtp.password".equals(setting.key())) {
                String masked =
                    setting.value() == null || setting.value().isBlank()
                        ? ""
                        : encryptionService.maskValue(encryptionService.decrypt(setting.value()));
                return new SettingResponse(
                    setting.key(), masked, setting.description(), setting.updatedAt());
              }
              return setting;
            })
        .collect(Collectors.toList());
  }

  @Transactional
  public void updateSmtpSettings(Map<String, String> settings, Long userId) {
    for (String key : settings.keySet()) {
      if (!ALLOWED_SMTP_KEYS.contains(key)) {
        throw new IllegalArgumentException("허용되지 않는 SMTP 설정 키: " + key);
      }
    }

    // smtp.port 범위 검증 — 1~65535 범위를 벗어나면 400 Bad Request
    if (settings.containsKey("smtp.port")) {
      String portStr = settings.get("smtp.port");
      try {
        int port = Integer.parseInt(portStr);
        if (port < 1 || port > 65535) {
          throw new IllegalArgumentException("SMTP 포트 번호는 1에서 65535 사이여야 합니다. 입력값: " + port);
        }
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("SMTP 포트 번호가 유효하지 않습니다: " + portStr);
      }
    }

    boolean hasMaskedPassword = isMaskedApiKey(settings.get("smtp.password"));

    Map<String, String> filtered =
        settings.entrySet().stream()
            .filter(e -> !(hasMaskedPassword && "smtp.password".equals(e.getKey())))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

    Map<String, String> toUpdate =
        filtered.entrySet().stream()
            .collect(
                Collectors.toMap(
                    Map.Entry::getKey,
                    e ->
                        "smtp.password".equals(e.getKey()) && !e.getValue().isBlank()
                            ? encryptionService.encrypt(e.getValue())
                            : e.getValue()));

    if (!toUpdate.isEmpty()) {
      settingsRepository.updateSettings(toUpdate, userId);
    }
  }

  @Transactional(readOnly = true)
  public Map<String, String> getSmtpConfig() {
    return settingsRepository.findByPrefix("smtp").stream()
        .collect(
            Collectors.toMap(
                SettingResponse::key,
                setting -> {
                  if ("smtp.password".equals(setting.key())
                      && setting.value() != null
                      && !setting.value().isBlank()) {
                    return encryptionService.decrypt(setting.value());
                  }
                  return setting.value() != null ? setting.value() : "";
                }));
  }

  private void validateValues(Map<String, String> settings) {
    settings.forEach(
        (key, value) -> {
          switch (key) {
            case "ai.max_turns" -> {
              int v = Integer.parseInt(value);
              if (v < 1 || v > 50) throw new IllegalArgumentException("최대 턴 수는 1에서 50 사이여야 합니다");
            }
            case "ai.temperature" -> {
              double v = Double.parseDouble(value);
              if (v < 0 || v > 1)
                throw new IllegalArgumentException("Temperature는 0.0에서 1.0 사이여야 합니다");
            }
            case "ai.max_tokens" -> {
              int v = Integer.parseInt(value);
              if (v < 1 || v > 65536)
                throw new IllegalArgumentException("최대 토큰 수는 1에서 65536 사이여야 합니다");
            }
            case "ai.session_max_tokens" -> {
              int v = Integer.parseInt(value);
              if (v < 1000 || v > 200000)
                throw new IllegalArgumentException("세션 최대 토큰 수는 1000에서 200000 사이여야 합니다");
            }
            case "ai.system_prompt" -> {
              if (value == null || value.isBlank())
                throw new IllegalArgumentException("시스템 프롬프트는 비어있을 수 없습니다");
            }
            case "ai.api_key" -> {
              if (value == null || value.isBlank())
                throw new IllegalArgumentException("API 키는 비어있을 수 없습니다");
            }
            case "ai.cli_oauth_token" -> {
              /* CLI OAuth 토큰은 비어있을 수 있음 (구독 미사용 시) */
            }
            case "ai.agent_type" -> {
              // opencode 추가: opencode 는 배포 환경의 opencode auth 에 의존하므로 별도 자격증명 불필요
              if (!Set.of("sdk", "cli", "cli-api", "opencode").contains(value))
                throw new IllegalArgumentException("에이전트 유형은 sdk, cli, cli-api, opencode 중 하나여야 합니다");
            }
            case "embedding.provider" -> {
              if (!Set.of("OLLAMA", "VOYAGE", "OPENAI").contains(value))
                throw new IllegalArgumentException(
                    "임베딩 provider 는 OLLAMA, VOYAGE, OPENAI 중 하나여야 합니다");
            }
            default -> {
              /* ai.model is a free-form string, validated by frontend dropdown */
            }
          }
        });
  }

  /**
   * 임베딩 설정의 항목 간 정합성을 검증한다 (이슈 #322, #323).
   *
   * <p>키를 하나씩 보는 {@link #validateValues}로는 "provider 는 OPENAI 인데 base_url 이 Ollama 주소"
   * 같은 조합 오류를 잡을 수 없다. 잘못된 조합이 저장되면 실패가 설정 화면이 아니라 한참 뒤
   * {@code EmbeddingProviderFactory} 런타임에야 드러나므로, 저장 시점에 막는다.
   *
   * <p><b>유효값 해석 규칙</b>: 페이로드에 <b>키가 없으면</b> 저장된 값으로 폴백하고, <b>키가 있으면
   * 빈 문자열이라도 그 값을 그대로</b> 쓴다. 마스킹된 api_key 는 호출부에서 이미 제거되므로 "키 없음"
   * = "기존 키 유지"로 해석되어, 저장된 키가 있는데 페이로드에 없다는 이유로 거부하는 회귀가 나지 않는다.
   * 반대로 사용자가 명시적으로 비운 빈 문자열은 그대로 "빈 값"으로 취급해 거부한다.
   */
  private void validateEmbeddingConsistency(Map<String, String> settings) {
    // 임베딩 키가 하나도 없는 저장(예: AI 탭 저장)은 검증 대상이 아니다.
    if (settings.keySet().stream().noneMatch(ALLOWED_EMBEDDING_KEYS::contains)) return;

    String provider = effectiveValue(settings, "embedding.provider").orElse("OLLAMA");
    String model = effectiveValue(settings, "embedding.model").orElse("");
    String baseUrl = effectiveValue(settings, "embedding.base_url").orElse("");

    // 모델/base_url 은 어떤 provider 든 비어 있으면 안 된다 (페이로드에 명시된 경우에 한해 검사).
    if (settings.containsKey("embedding.model") && model.isBlank())
      throw new IllegalArgumentException("임베딩 모델은 비어있을 수 없습니다");
    if (settings.containsKey("embedding.base_url") && baseUrl.isBlank())
      throw new IllegalArgumentException("임베딩 Base URL 은 비어있을 수 없습니다");

    // base_url 형식 — http/https 스킴과 호스트를 갖춘 절대 URL 이어야 한다.
    if (!baseUrl.isBlank() && embeddingUrlScheme(baseUrl).isEmpty())
      throw new IllegalArgumentException(
          "임베딩 Base URL 은 http:// 또는 https:// 로 시작하는 올바른 주소여야 합니다: " + baseUrl);

    if (!"OPENAI".equals(provider)) return;

    // OPENAI 는 공개 API/프록시 모두 TLS 를 쓴다. http 주소가 남아 있다는 것은 Ollama 등 다른
    // provider 주소가 그대로 남은 불일치 신호이므로 거부한다 (평문 http 자체 호스팅 프록시는 미지원).
    if (!baseUrl.isBlank() && !"https".equals(embeddingUrlScheme(baseUrl).orElse("")))
      throw new IllegalArgumentException(
          "OpenAI 임베딩 provider 의 Base URL 은 https 주소여야 합니다. 현재 값: "
              + baseUrl
              + " (provider 를 변경했다면 Base URL 도 함께 변경하세요)");

    // OPENAI 는 Bearer 인증 필수 — 저장된 키도 없고 새 키도 없으면 저장을 막는다.
    boolean hasStoredKey =
        settingsRepository.getValue("embedding.api_key").filter(v -> !v.isBlank()).isPresent();
    String submittedKey = settings.get("embedding.api_key");
    boolean keyAvailable =
        submittedKey != null ? !submittedKey.isBlank() : hasStoredKey;
    if (!keyAvailable) throw new IllegalArgumentException("OpenAI 임베딩 provider 에는 API 키가 필요합니다");
  }

  /** 페이로드에 키가 있으면 그 값(빈 문자열 포함), 없으면 저장된 값을 반환한다. */
  private Optional<String> effectiveValue(Map<String, String> settings, String key) {
    if (settings.containsKey(key)) return Optional.ofNullable(settings.get(key));
    return settingsRepository.getValue(key);
  }

  /** base_url 의 http/https 스킴을 반환한다. 절대 URL 이 아니거나 호스트가 없으면 empty. */
  private Optional<String> embeddingUrlScheme(String baseUrl) {
    try {
      java.net.URI uri = java.net.URI.create(baseUrl.trim());
      String scheme = uri.getScheme();
      if (uri.getHost() == null || scheme == null) return Optional.empty();
      String lower = scheme.toLowerCase(java.util.Locale.ROOT);
      return "http".equals(lower) || "https".equals(lower) ? Optional.of(lower) : Optional.empty();
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
