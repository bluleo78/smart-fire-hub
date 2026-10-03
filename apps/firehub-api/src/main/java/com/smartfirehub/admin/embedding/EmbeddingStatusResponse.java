package com.smartfirehub.admin.embedding;

import java.time.OffsetDateTime;

/**
 * GET /admin/embedding/status 응답. 미설정이면 configured=false·model/dimension=null·embedded=0. job 은 마지막
 * 재임베딩 상태(없으면 null) — FAILED 사유를 설정 화면에 보여준다.
 */
public record EmbeddingStatusResponse(
    boolean configured,
    String model,
    Integer dimension,
    Counts datasets,
    Counts documentChunks,
    Job job) {

  /** 총 개수와 현재 공간 임베딩 완료 개수 쌍. */
  public record Counts(long total, long embedded) {}

  /** 테넌트 재임베딩 잡 상태. */
  public record Job(String status, String lastError, OffsetDateTime updatedAt) {}
}
