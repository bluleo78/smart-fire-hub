package com.smartfirehub.platform.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.smartfirehub.audit.time.StorageZoneDateTimeSerializer;
import java.time.LocalDateTime;

/**
 * 운영자 목록 화면의 테넌트 한 줄.
 *
 * <p>도메인 데이터(데이터셋·파이프라인 수 등)는 담지 않는다 — 설계서 §4 가 크로스테넌트 도메인 조회를 제공하지 않기로 결정했다. 여기 실리는 것은 전역 테이블의 사실과
 * 멤버 수 집계뿐이다.
 */
public record TenantSummaryResponse(
    Long id,
    String slug,
    String name,
    String status,
    int memberCount,
    /**
     * tenant.created_at(TIMESTAMP DEFAULT NOW(), 저장 TZ 벽시계) — JSON 에는 저장 TZ 오프셋이 붙는다(WD-11). 예전 고정
     * 형식(@JsonFormat, 오프셋 없음)은 운영(UTC 저장)에서 admin 이 날짜를 잘라 KST 운영자에게 어긋났다.
     */
    @JsonSerialize(using = StorageZoneDateTimeSerializer.class) LocalDateTime createdAt) {}
