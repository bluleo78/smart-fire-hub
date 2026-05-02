package com.smartfirehub.dataset.dto;

import java.util.List;

/**
 * 데이터셋의 기본 키 컬럼 집합을 한 번에 갱신하기 위한 요청 DTO.
 *
 * <p>복합 PK 의 경우 단일 컬럼별 PUT 으로는 중간 상태가 unique 하지 않아 실패하므로,
 * 최종 PK 컬럼 ID 목록을 한 번에 받아 트랜잭션 안에서 일괄 적용한다.
 */
public record UpdatePrimaryKeysRequest(List<Long> columnIds) {}
