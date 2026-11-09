package com.smartfirehub.dataset.search;

import com.smartfirehub.global.validation.SearchQueryLimits;
import jakarta.validation.constraints.Size;

/**
 * POST /datasets/search 요청 body.
 *
 * <p>query 는 최대 {@link SearchQueryLimits#MAX_QUERY_LENGTH}자(WD-600) — 임베딩 provider·키워드 SQL 로 그대로
 * 가므로 상한을 둔다. 컨트롤러의 {@code @Valid} 로 검증되고 초과 시 400.
 */
public record DatasetSearchRequest(
    @Size(max = SearchQueryLimits.MAX_QUERY_LENGTH, message = SearchQueryLimits.QUERY_TOO_LONG)
        String query, // 필수
    Integer topK, // null → 기본 10, 최대 20
    DatasetSearchMode mode, // null → HYBRID
    String storageType) {} // "TABLE" | "DOCUMENT" | "FILE"; null → 전체 유형
