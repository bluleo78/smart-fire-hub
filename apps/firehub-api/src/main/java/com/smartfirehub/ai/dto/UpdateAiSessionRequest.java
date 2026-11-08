package com.smartfirehub.ai.dto;

import jakarta.validation.constraints.NotBlank;

/** AI 세션 제목 수정 요청. 빈 제목 400 문구는 화면에 보이므로 한국어(WD-570, 예전 "Title is required" 대체). */
public record UpdateAiSessionRequest(@NotBlank(message = "제목을 입력하세요.") String title) {}
