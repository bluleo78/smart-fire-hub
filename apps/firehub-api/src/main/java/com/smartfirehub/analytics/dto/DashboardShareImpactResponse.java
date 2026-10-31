package com.smartfirehub.analytics.dto;

/**
 * 대시보드 공유를 켜거나 끌 때 함께 영향받는 「내」 차트·저장 쿼리 개수(WD-325). 설정 창이 공유 스위치 아래 안내 문구에 쓴다.
 *
 * <p>대시보드를 공유하면 위젯이 쓰는 차트·쿼리 중 요청자 본인 것만 함께 공유로 바뀌고(WD-194), 공유를 꺼도 되돌리지 않는다 — 어느 것이 대시보드 때문에 공개됐는지
 * 기록하지 않아, 작성자가 따로 공개한 것까지 비공개로 만들 수 있기 때문이다. 그래서 켤 때는 새로 공개될 개수를, 끌 때는 계속 공개로 남는 개수를 알린다. 남의
 * 차트·쿼리는 세지 않는다(공유 여부를 그 주인만 정한다).
 *
 * @param privateChartCount 공유를 켜면 새로 공개되는 내 차트 수(지금 비공개)
 * @param privateQueryCount 공유를 켜면 새로 공개되는 내 저장 쿼리 수(지금 비공개)
 * @param sharedChartCount 공유를 꺼도 계속 공개로 남는 내 차트 수(지금 공개)
 * @param sharedQueryCount 공유를 꺼도 계속 공개로 남는 내 저장 쿼리 수(지금 공개)
 */
public record DashboardShareImpactResponse(
    int privateChartCount, int privateQueryCount, int sharedChartCount, int sharedQueryCount) {}
