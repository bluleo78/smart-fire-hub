package com.smartfirehub.job.dto;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public record AsyncJobStatusResponse(
    String jobId,
    String jobType,
    String stage,
    int progress,
    String message,
    Map<String, Object> metadata,
    String errorMessage,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    Long userId) {

  /**
   * 서버 안에서만 쓰는 메타데이터 키 — 작업 상태 응답·진행 SSE 로 내보내지 않는다(WD-250).
   *
   * <p>{@code filePath} 는 비동기 내보내기 파일의 서버 임시 디렉터리 절대 경로다. 다운로드({@code
   * DataExportService.getExportFile})가 DB 의 원본 메타데이터에서 이 값을 읽으므로 저장은 그대로 두고, 바깥으로 나가는 응답에서만 뺀다.
   */
  public static final Set<String> INTERNAL_METADATA_KEYS = Set.of("filePath");

  /** 내부 전용 키를 뺀 메타데이터 사본. null 이면 빈 맵. 원본 맵은 바꾸지 않는다. */
  public static Map<String, Object> publicMetadata(Map<String, Object> metadata) {
    if (metadata == null || metadata.isEmpty()) {
      return Collections.emptyMap();
    }
    if (INTERNAL_METADATA_KEYS.stream().noneMatch(metadata::containsKey)) {
      return metadata;
    }
    Map<String, Object> copy = new HashMap<>(metadata);
    copy.keySet().removeAll(INTERNAL_METADATA_KEYS);
    return copy;
  }

  /** 응답용 사본 — 메타데이터에서 내부 전용 키만 뺀다. */
  public AsyncJobStatusResponse withoutInternalMetadata() {
    return new AsyncJobStatusResponse(
        jobId,
        jobType,
        stage,
        progress,
        message,
        publicMetadata(metadata),
        errorMessage,
        createdAt,
        updatedAt,
        userId);
  }
}
