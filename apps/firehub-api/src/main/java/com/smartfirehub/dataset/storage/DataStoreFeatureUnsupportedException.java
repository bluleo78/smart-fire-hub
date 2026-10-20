package com.smartfirehub.dataset.storage;

import com.smartfirehub.global.exception.CodedApiException;
import org.springframework.http.HttpStatus;

/**
 * 선택된 데이터셋 저장소(Tibero·MySQL 등)가 아직 지원하지 않는 기능을 호출했을 때 던지는 예외.
 *
 * <p>왜 필요한가: 지원하지 않는 기능을 조용히 무시하면 데이터가 어긋나므로, 422 와 고정 코드({@code DATA_STORE_FEATURE_UNSUPPORTED}) 로
 * 명확히 거부해 웹이 이유를 그대로 보여줄 수 있게 한다.
 */
public class DataStoreFeatureUnsupportedException extends CodedApiException {

  /** 오류 코드 상수 — 웹·테스트가 문자열 대신 참조한다. */
  public static final String CODE = "DATA_STORE_FEATURE_UNSUPPORTED";

  /**
   * @param backend 기능을 지원하지 않는 저장소 백엔드(메시지에 표시 이름으로 들어간다)
   * @param featureKorean 지원하지 않는 기능의 한국어 이름(예: "GEOMETRY 컬럼")
   */
  public DataStoreFeatureUnsupportedException(DataStoreBackend backend, String featureKorean) {
    super(
        HttpStatus.UNPROCESSABLE_ENTITY,
        CODE,
        featureKorean + ": " + displayName(backend) + " 저장소에서 아직 지원하지 않습니다");
  }

  /** 사용자에게 보여 줄 백엔드 이름. 메시지가 특정 백엔드에 묶이지 않도록 호출자가 넘긴 백엔드로 만든다. */
  private static String displayName(DataStoreBackend backend) {
    return switch (backend) {
      case POSTGRESQL -> "PostgreSQL";
      case TIBERO -> "Tibero";
      case MYSQL -> "MySQL";
    };
  }
}
