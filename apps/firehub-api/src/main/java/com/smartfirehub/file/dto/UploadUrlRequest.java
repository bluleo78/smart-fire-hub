package com.smartfirehub.file.dto;

import java.util.List;

/** 업로드 URL 발급 요청 — robotId(선택, 없으면 앱이 "web" 처리)와 파일별 확장자 목록. */
public record UploadUrlRequest(String robotId, List<FileSpec> files) {
  /** 개별 파일 스펙 — 확장자만 제공(키/파일명은 앱이 생성). */
  public record FileSpec(String ext) {}
}
