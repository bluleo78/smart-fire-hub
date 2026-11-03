package com.smartfirehub.global.platformdb;

import com.smartfirehub.global.exception.CodedApiException;
import org.springframework.http.HttpStatus;

/**
 * 현재 플랫폼 DB 가 지원하지 않는 기능을 반드시 요구하는 호출에 던지는 예외.
 *
 * <p>왜 필요한가: 지원하지 않는 기능을 조용히 무시하면 결과가 어긋나므로 422 와 고정 코드({@code PLATFORM_DB_FEATURE_UNSUPPORTED}) 로
 * 명확히 거부한다. 지금은 던지는 곳이 없다 — 꺼진 기능은 서비스가 건너뛰는 쪽이 기본이고, 이 예외는 건너뛸 수 없는 호출을 위한 계약이다. {@code
 * DataStoreFeatureUnsupportedException} 을 본떴다.
 */
public class PlatformDbFeatureUnsupportedException extends CodedApiException {

  /** 오류 코드 상수 — 웹·테스트가 문자열 대신 참조한다. */
  public static final String CODE = "PLATFORM_DB_FEATURE_UNSUPPORTED";

  /**
   * @param feature 지원되지 않는 기능
   */
  public PlatformDbFeatureUnsupportedException(PlatformDbFeature feature) {
    super(HttpStatus.UNPROCESSABLE_ENTITY, CODE, "플랫폼 DB 가 지원하지 않는 기능입니다: " + feature.name());
  }
}
