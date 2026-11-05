package com.smartfirehub.proactive.repository.mybatis;

import java.time.LocalDateTime;

/**
 * 프로액티브 메시지 조회 결과의 평평한 행(WD-487 PoC).
 *
 * <p>매퍼는 SQL 결과를 이 record 로만 돌려주고, JSON 역직렬화·요약 가림 같은 행 단위 로직은 {@code
 * MyBatisProactiveMessageRepository} 가 수행한다(벤더별 SQL 과 자바 규칙을 분리하기 위함).
 *
 * @param contentJson content(jsonb)를 문자열로 읽은 값 — null 이면 빈 맵으로 취급한다
 * @param canViewReport 수신자 = 작업 주인이면 1, 아니면 0 (불리언 표현이 벤더마다 달라 정수로 받는다)
 * @param reportLevelRank 연결 리포트의 등급 순위, 없으면 null
 */
public record ProactiveMessageRow(
    Long id,
    Long userId,
    Long jobId,
    Long executionId,
    String title,
    String contentJson,
    String messageType,
    Boolean read,
    LocalDateTime readAt,
    String jobName,
    LocalDateTime createdAt,
    int canViewReport,
    Integer reportLevelRank) {}
