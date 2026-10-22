package com.smartfirehub.dbexecutor.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * {@code POST /execute/probe} 요청.
 *
 * @param tenantId 테넌트 id
 * @param query 결과 컬럼을 알고 싶은 SELECT(WITH) 한 문장
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProbeRequest(Long tenantId, String query) {}
