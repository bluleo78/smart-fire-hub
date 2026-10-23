package com.smartfirehub.dbexecutor.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * {@code POST /execute/query} 요청.
 *
 * @param tenantId 테넌트 id
 * @param query 조회(SELECT·WITH) 한 문장
 * @param maxRows 돌려줄 최대 행 수(1..10000)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record QueryRequest(Long tenantId, String query, Integer maxRows) {}
