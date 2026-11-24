package com.smartfirehub.analytics.dto;

import com.smartfirehub.global.validation.TextRules;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** 화면 표시 데이터(AI 표 위젯)의 내보내기 가능 여부 판정 요청 — 위젯이 들고 있는 SQL(설계 결정 7). */
public record ExportCheckRequest(
    // WD-1086: 공백만·NBSP·Cf 만인 위젯 SQL 은 DB 까지 가 문법 오류(500)를 낸다 — HAS_TEXT 로 400 필드 오류로 막는다
    @NotBlank(message = TextRules.SQL_BLANK_MESSAGE)
        @Pattern(regexp = TextRules.HAS_TEXT, message = TextRules.SQL_BLANK_MESSAGE)
        String sql) {}
