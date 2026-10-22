package com.smartfirehub.dbexecutor.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 탐지한 결과 컬럼 하나(ResultSetMetaData). 1단계 api 는 이름만 쓰고, 타입 정보는 2단계 임시 데이터셋 생성 때 쓴다(스펙 §4.2).
 *
 * @param name 드라이버가 준 원래 이름(바꾸지 않음 — 따옴표 없는 별칭은 대문자, 따옴표 별칭은 쓴 그대로). 정규화는 api 가 한다
 * @param jdbcType {@link java.sql.Types} 값
 * @param typeName 드라이버 타입 이름(예: NUMBER, VARCHAR)
 * @param precision 정밀도
 * @param scale 소수 자릿수(맨 NUMBER 는 음수일 수 있다)
 */
public record ProbeColumn(
    String name,
    @JsonProperty("jdbc_type") int jdbcType,
    @JsonProperty("type_name") String typeName,
    int precision,
    int scale) {}
