package com.smartfirehub.job.exception;

/**
 * 비동기 작업(async_job)을 찾을 수 없을 때 던진다 — 404 로 응답한다(WD-203).
 *
 * <p>예전에는 {@link IllegalArgumentException}("Job not found: …")을 던져 400 + 영어 문구가 나갔다. 웹의 진행 추적 훅은
 * 400/403/404 를 "더 기다려도 소용없는 상태"로 보고 추적을 멈추므로, 상태 코드는 의미대로 404 를 쓰고 문구는 사용자에게 그대로 보여 줄 수 있게 한국어로
 * 둔다. 다른 테넌트의 작업은 RLS 로 보이지 않으므로 이 예외로 귀결된다(존재 여부를 노출하지 않음).
 */
public class JobNotFoundException extends RuntimeException {

  public JobNotFoundException(String jobId) {
    super("작업을 찾을 수 없습니다. (작업 ID: " + jobId + ")");
  }
}
