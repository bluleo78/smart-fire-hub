package com.smartfirehub.file.dto;

/** 오브젝트 1건의 목록 표시용 메타. key = "&lt;prefix&gt;&lt;파일명&gt;"이며, 표시명은 프론트가 마지막 경로 세그먼트로 얻는다(S3 방식). */
public record ObjectItemResponse(String key, long size, String lastModified) {}
