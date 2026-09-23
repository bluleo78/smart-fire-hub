package com.smartfirehub.dataset.rowsearch.dto;

import com.smartfirehub.dataset.rowsearch.RowFilter;
import java.util.List;

/**
 * 행 검색 요청. mode: HYBRID(기본) | SEMANTIC | KEYWORD. limit 기본 20·최대 100. columns 생략 시 GEOMETRY 를 뺀 전체
 * 사용자 컬럼을 반환한다.
 */
public record RowSearchRequest(
    String query, String mode, Integer limit, List<RowFilter.Condition> filters, List<String> columns) {}
