package com.smartfirehub.proactive.dto;

import java.time.LocalDateTime;

/**
 * 전역 리포트 목록의 행 1건.
 *
 * <p>리포트 본문(htmlContent)은 수십 KB에 달하므로 목록에는 절대 포함하지 않는다. 본문은 행을 클릭해 진입하는
 * /jobs/{jobId}/executions/{executionId}/html 로만 조회한다.
 */
public record ReportListItemResponse(
    Long executionId,
    Long jobId,
    /** 리포트가 어느 스마트 작업에서 나왔는지 표시하기 위한 잡 이름 */
    String jobName,
    /** result.title이 비어 있으면 잡 이름으로 대체된 유효 제목 */
    String title,
    /** result.summary — 목록에서 제목 아래 한 줄 요약으로 노출. 없으면 null */
    String summary,
    LocalDateTime completedAt) {}
