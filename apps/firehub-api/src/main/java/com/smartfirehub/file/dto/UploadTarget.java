package com.smartfirehub.file.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 발급된 업로드 대상 — 앱이 생성한 오브젝트 키 + 클라이언트가 PUT할 presigned URL.
 *
 * <p>요청 files 와 같은 순서·같은 개수로 내려가 화면이 순서로 원본 파일과 짝짓는다. 파일별 검증에 실패한 자리는 key·uploadUrl 없이 error 만
 * 싣는다(WD-776 — 규칙은 ObjectKeyGenerator.generateKey 참고). null 필드는 응답에서 뺀다(성공 대상은 예전 응답 모양 그대로).
 *
 * @param key 생성한 전체 오브젝트 키(실패면 null)
 * @param uploadUrl presigned PUT URL(실패면 null)
 * @param error 이 파일을 발급하지 못한 이유(성공이면 null)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UploadTarget(String key, String uploadUrl, String error) {

  /** 발급 성공 대상. */
  public UploadTarget(String key, String uploadUrl) {
    this(key, uploadUrl, null);
  }

  /** 이 파일만 발급하지 못함(파일별 검증 실패, WD-776) — 사유만 싣는다. */
  public static UploadTarget failed(String error) {
    return new UploadTarget(null, null, error);
  }
}
