package com.smartfirehub.analytics.dto;

/** 내보내기 가능 여부 — 숨김·파싱 실패·정책 위반은 모두 false(구분 불가, 존재 오라클이 되지 않게). */
public record ExportCheckResponse(boolean exportAllowed) {}
