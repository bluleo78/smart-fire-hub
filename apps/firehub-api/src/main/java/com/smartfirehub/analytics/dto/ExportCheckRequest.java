package com.smartfirehub.analytics.dto;

import jakarta.validation.constraints.NotBlank;

/** 화면 표시 데이터(AI 표 위젯)의 내보내기 가능 여부 판정 요청 — 위젯이 들고 있는 SQL(설계 결정 7). */
public record ExportCheckRequest(@NotBlank String sql) {}
