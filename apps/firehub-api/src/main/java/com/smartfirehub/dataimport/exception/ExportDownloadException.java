package com.smartfirehub.dataimport.exception;

import org.springframework.http.HttpStatus;

/**
 * 비동기 내보내기 파일을 내려받을 수 없을 때 던진다 — 전역 핸들러가 {@link #status()} 로 응답하고 message 를 그대로 내보낸다(WD-250).
 *
 * <p>예전에는 IllegalArgumentException·AccessDeniedException·IllegalStateException 에 영어 문구(「Job not
 * found: …」·「Access denied: not the owner of job …」·「Export file path not found in job metadata」
 * 등)를 실어 400/403/409 가 섞여 나갔고, 없는 작업이 작업 상태 API(404)와 달리 400 이었다. 사유마다 상태와 한국어 문구를 이 클래스의 팩토리로만 만들게
 * 해 호출처마다 문구가 흩어지지 않게 한다. 다운로드 요청은 파일을 기대하므로 핸들러가 Content-Type 을 JSON 으로 정해 오류 본문이 협상 실패로 사라지지 않게
 * 한다.
 */
public class ExportDownloadException extends RuntimeException {

  /** 작업이 없거나(다른 테넌트 포함) 내보내기 작업이 아닐 때 — 404. */
  public static final String NOT_FOUND_MESSAGE = "내보내기 작업을 찾을 수 없습니다. 이미 삭제되었을 수 있습니다.";

  /** 같은 테넌트의 다른 사용자가 시작한 내보내기일 때 — 403. */
  public static final String NOT_OWNER_MESSAGE = "이 내보내기를 시작한 사용자만 파일을 내려받을 수 있습니다.";

  /** 아직 진행 중(대기·내보내는 중)일 때 — 409. */
  public static final String NOT_FINISHED_MESSAGE = "아직 내보내기가 끝나지 않았습니다. 잠시 후 다시 시도해 주세요.";

  /** 내보내기가 실패해 파일이 만들어지지 않았을 때 — 409. */
  public static final String FAILED_MESSAGE = "내보내기가 실패해 내려받을 파일이 없습니다. 다시 내보내 주세요.";

  /** 완료됐지만 보관 기간이 지나 파일이 정리됐을 때 — 409. */
  public static final String CLEANED_UP_MESSAGE = "내보내기 파일이 정리되어 더 이상 내려받을 수 없습니다. 다시 내보내 주세요.";

  private final HttpStatus status;

  private ExportDownloadException(HttpStatus status, String message) {
    super(message);
    this.status = status;
  }

  /** 응답 HTTP 상태. */
  public HttpStatus status() {
    return status;
  }

  /** 작업이 없거나 내보내기 작업이 아님 — 존재 여부를 구분해 드러내지 않도록 같은 404 로 답한다. */
  public static ExportDownloadException notFound() {
    return new ExportDownloadException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE);
  }

  /** 남이 시작한 내보내기. */
  public static ExportDownloadException notOwner() {
    return new ExportDownloadException(HttpStatus.FORBIDDEN, NOT_OWNER_MESSAGE);
  }

  /** 아직 끝나지 않은 내보내기. */
  public static ExportDownloadException notFinished() {
    return new ExportDownloadException(HttpStatus.CONFLICT, NOT_FINISHED_MESSAGE);
  }

  /** 실패한 내보내기. */
  public static ExportDownloadException failed() {
    return new ExportDownloadException(HttpStatus.CONFLICT, FAILED_MESSAGE);
  }

  /** 파일이 정리된(또는 경로 기록이 없는) 내보내기. */
  public static ExportDownloadException cleanedUp() {
    return new ExportDownloadException(HttpStatus.CONFLICT, CLEANED_UP_MESSAGE);
  }
}
