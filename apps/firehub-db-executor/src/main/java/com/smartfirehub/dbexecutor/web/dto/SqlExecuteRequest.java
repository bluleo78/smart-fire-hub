package com.smartfirehub.dbexecutor.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * {@code POST /execute/sql} 요청(api {@code DbExecutorClient} 가 camelCase 로 보낸다).
 *
 * @param tenantId 테넌트 id — 저장소 기술자 조회 키
 * @param query 본문 한 문장(api 가 만든 INSERT…SELECT·MERGE 또는 사용자 DML)
 * @param preStatements 선행 문장(REPLACE 의 {@code DELETE FROM "S"."T"}), 없으면 빈 목록 또는 null
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SqlExecuteRequest(Long tenantId, String query, List<String> preStatements) {}
