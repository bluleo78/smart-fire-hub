package com.smartfirehub.analytics.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 저장 쿼리 수정 요청 — 모든 필드는 생략(null)이면 "변경 없음"이다.
 *
 * <p>이름·SQL 은 생략은 되지만 빈 값(공백만 포함)으로는 바꿀 수 없다 — 리포지토리는 null 이 아니면 그대로 덮어써, 공백 SQL 이 저장되면 그 쿼리는 실행마다
 * 「SQL 이 비어 있습니다」로 실패하고 빈 이름은 목록에서 고를 수 없다(생성은 {@code @NotBlank} 로 이미 막는다). {@code @Pattern} 은 null
 * 을 통과시키므로 생략은 그대로 허용된다.
 */
public record UpdateSavedQueryRequest(
    @Size(max = 200) @Pattern(regexp = "(?s).*\\S.*", message = "이름을 비울 수 없습니다.") String name,
    String description,
    @Pattern(regexp = "(?s).*\\S.*", message = "SQL을 비울 수 없습니다.") String sqlText,
    Long datasetId,
    @Size(max = 100) String folder,
    Boolean isShared) {}
