package com.smartfirehub.settings.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.Map;

/** 설정 일괄 수정 요청. 빈 맵은 바꿀 것이 없으므로 400 — 문구는 화면에 그대로 보이므로 한국어(WD-570, 예전 영어 문구 대체). */
public record UpdateSettingsRequest(
    @NotEmpty(message = "변경할 설정이 없습니다.") Map<String, String> settings) {}
