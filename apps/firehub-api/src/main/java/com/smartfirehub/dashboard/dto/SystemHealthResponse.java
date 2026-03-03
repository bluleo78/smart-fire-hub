package com.smartfirehub.dashboard.dto;

public record SystemHealthResponse(PipelineHealth pipelineHealth, DatasetHealth datasetHealth) {

  public record PipelineHealth(
      int total,
      int healthy, // 최근 실행 성공 or 실행 기록 없는 신규
      int failing, // 최근 실행 실패
      int running, // 현재 실행 중
      int disabled // 비활성
      ) {}

  public record DatasetHealth(
      int total,
      int fresh, // 24h 내 갱신
      int stale, // 24h+ 미갱신 (source 데이터셋만)
      int empty // 행 0건
      ) {}
}
