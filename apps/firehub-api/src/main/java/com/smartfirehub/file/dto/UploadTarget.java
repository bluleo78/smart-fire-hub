package com.smartfirehub.file.dto;

/** 발급된 업로드 대상 — 앱이 생성한 오브젝트 키 + 클라이언트가 PUT할 presigned URL. */
public record UploadTarget(String key, String uploadUrl) {}
