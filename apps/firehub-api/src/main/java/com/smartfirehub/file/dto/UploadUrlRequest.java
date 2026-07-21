package com.smartfirehub.file.dto;

import java.util.List;

/** 업로드 URL 발급 요청 — 파일별 스펙 목록. 최종 키는 앱이 "&lt;prefix&gt;&lt;filename&gt;"으로 생성한다(S3 방식). */
public record UploadUrlRequest(List<FileSpec> files) {
  /** 개별 파일 스펙 — 원본 파일명. 앱이 prefix를 붙여 키를 만들고, basename만 취해 프리픽스 격리를 강제한다. */
  public record FileSpec(String filename) {}
}
