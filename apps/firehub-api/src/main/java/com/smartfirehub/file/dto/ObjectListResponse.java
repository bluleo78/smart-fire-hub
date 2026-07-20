package com.smartfirehub.file.dto;

import java.util.List;

/** 오브젝트 목록 페이지 응답. nextToken 이 있으면 다음 페이지 존재. */
public record ObjectListResponse(
    List<ObjectItemResponse> objects, String nextToken, boolean hasMore) {}
