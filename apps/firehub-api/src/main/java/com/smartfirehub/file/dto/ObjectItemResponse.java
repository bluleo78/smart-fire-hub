package com.smartfirehub.file.dto;

/**
 * 오브젝트 1건의 목록 표시용 메타.
 * key = "&lt;prefix&gt;&lt;상대경로&gt;"(전체 키, presigned URL 발급용). name = prefix를 제외한 상대경로로,
 * 폴더 업로드 시 하위 구조가 목록에 그대로 보이도록 표시명으로 쓴다(S3 방식).
 */
public record ObjectItemResponse(String key, String name, long size, String lastModified) {}
