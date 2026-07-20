package com.smartfirehub.file.dto;

import java.util.List;

/** 업로드 URL 발급 응답 — 대상 목록 + 만료(초). */
public record UploadUrlResponse(List<UploadTarget> targets, int expiresInSeconds) {}
