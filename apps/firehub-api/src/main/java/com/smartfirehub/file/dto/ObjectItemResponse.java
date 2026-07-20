package com.smartfirehub.file.dto;

/** 오브젝트 1건의 목록 표시용 메타. */
public record ObjectItemResponse(String key, long size, String lastModified) {}
