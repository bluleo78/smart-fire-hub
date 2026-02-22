package com.smartfirehub.settings.service;

import com.smartfirehub.settings.dto.SettingResponse;
import com.smartfirehub.settings.repository.SettingsRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class SettingsService {

    private static final Set<String> ALLOWED_AI_KEYS = Set.of(
            "ai.model", "ai.max_turns", "ai.system_prompt", "ai.temperature", "ai.max_tokens", "ai.session_max_tokens"
    );

    private final SettingsRepository settingsRepository;

    public SettingsService(SettingsRepository settingsRepository) {
        this.settingsRepository = settingsRepository;
    }

    public List<SettingResponse> getByPrefix(String prefix) {
        return settingsRepository.findByPrefix(prefix);
    }

    public Optional<String> getValue(String key) {
        return settingsRepository.getValue(key);
    }

    public Map<String, String> getAsMap(String prefix) {
        return settingsRepository.findByPrefix(prefix).stream()
                .collect(Collectors.toMap(SettingResponse::key, SettingResponse::value));
    }

    public void updateSettings(Map<String, String> settings, Long userId) {
        for (String key : settings.keySet()) {
            if (!ALLOWED_AI_KEYS.contains(key)) {
                throw new IllegalArgumentException("허용되지 않는 설정 키: " + key);
            }
        }
        validateValues(settings);
        settingsRepository.updateSettings(settings, userId);
    }

    private void validateValues(Map<String, String> settings) {
        settings.forEach((key, value) -> {
            switch (key) {
                case "ai.max_turns" -> {
                    int v = Integer.parseInt(value);
                    if (v < 1 || v > 50) throw new IllegalArgumentException("최대 턴 수는 1~50 사이여야 합니다");
                }
                case "ai.temperature" -> {
                    double v = Double.parseDouble(value);
                    if (v < 0 || v > 1) throw new IllegalArgumentException("Temperature는 0.0~1.0 사이여야 합니다");
                }
                case "ai.max_tokens" -> {
                    int v = Integer.parseInt(value);
                    if (v < 1 || v > 65536) throw new IllegalArgumentException("최대 토큰 수는 1~65536 사이여야 합니다");
                }
                case "ai.session_max_tokens" -> {
                    int v = Integer.parseInt(value);
                    if (v < 1000 || v > 200000) throw new IllegalArgumentException("세션 최대 토큰 수는 1000~200000 사이여야 합니다");
                }
                case "ai.system_prompt" -> {
                    if (value == null || value.isBlank()) throw new IllegalArgumentException("시스템 프롬프트는 비어있을 수 없습니다");
                }
                default -> { /* ai.model is a free-form string, validated by frontend dropdown */ }
            }
        });
    }
}
