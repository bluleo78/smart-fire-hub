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

  private final SettingsRepository settingsRepository;
  private final EncryptionService encryptionService;

  @Transactional(readOnly = true)
  public List<SettingResponse> getByPrefix(String prefix) {
    return settingsRepository.findByPrefix(prefix).stream()
        .map(
            setting -> {
              if ("ai.api_key".equals(setting.key())
                  || "ai.cli_oauth_token".equals(setting.key())) {
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
                SettingResponse::key,
                s -> s.value() != null ? s.value() : "",
                (a, b) -> b));
  }

  public void updateSettings(Map<String, String> settings, Long userId) {
    for (String key : settings.keySet()) {
      if (!ALLOWED_AI_KEYS.contains(key)) {
        throw new IllegalArgumentException("허용되지 않는 설정 키: " + key);
      }
    }

    boolean hasMaskedApiKey = isMaskedApiKey(settings.get("ai.api_key"));
    boolean hasMaskedCliToken = isMaskedApiKey(settings.get("ai.cli_oauth_token"));

    // Skip validation and save for masked values (unchanged by user)
    Map<String, String> filtered =
        settings.entrySet().stream()
            .filter(e -> !(hasMaskedApiKey && "ai.api_key".equals(e.getKey())))
            .filter(e -> !(hasMaskedCliToken && "ai.cli_oauth_token".equals(e.getKey())))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    validateValues(filtered);

    Map<String, String> toUpdate =
        filtered.entrySet().stream()
            .collect(
                Collectors.toMap(
                    Map.Entry::getKey,
                    e ->
                        ("ai.api_key".equals(e.getKey()) || "ai.cli_oauth_token".equals(e.getKey()))
                            ? encryptionService.encrypt(e.getValue())
                            : e.getValue()));

    if (!toUpdate.isEmpty()) {
      settingsRepository.updateSettings(toUpdate, userId);
    }
  }

  private static boolean isMaskedApiKey(String value) {
    return value != null && value.startsWith("****");
  }

  @Transactional(readOnly = true)
  public Optional<String> getDecryptedApiKey() {
    return getValue("ai.api_key").filter(v -> !v.isBlank()).map(encryptionService::decrypt);
  }

  @Transactional(readOnly = true)
  public Optional<String> getDecryptedCliOauthToken() {
    return getValue("ai.cli_oauth_token").filter(v -> !v.isBlank()).map(encryptionService::decrypt);
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
              if (!Set.of("sdk", "cli", "cli-api").contains(value))
                throw new IllegalArgumentException("에이전트 유형은 sdk, cli, cli-api 중 하나여야 합니다");
            }
            default -> {
              /* ai.model is a free-form string, validated by frontend dropdown */
            }
          }
        });
  }
}
