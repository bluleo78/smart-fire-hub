package com.smartfirehub.dashboard.dto;

import java.util.List;

public record SystemHealthResponse(PipelineHealth pipelineHealth, DatasetHealth datasetHealth) {

  public record PipelineHealth(
      int total,
      int healthy, // 최근 실행 성공 or 실행 기록 없는 신규
      int failing, // 최근 실행 실패
      int running, // 현재 실행 중
      int disabled, // 비활성
      // 최근 7일간 일자별 파이프라인 실행 건수 (과거→오늘 순, 홈 대시보드 스파크라인용, #669)
      List<Integer> trend) {}

  public record DatasetHealth(
      int total,
      int fresh, // 24h 내 갱신
      int stale, // 24h+ 미갱신 (source 데이터셋만)
      int empty, // 행 0건
      // 최근 7일간 일자별 데이터셋 임포트/변경 건수 (과거→오늘 순, 홈 대시보드 스파크라인용, #669)
      List<Integer> trend) {}
}
