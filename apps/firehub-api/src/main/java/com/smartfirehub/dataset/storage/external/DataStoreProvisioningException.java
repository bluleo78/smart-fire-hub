package com.smartfirehub.dataset.storage.external;

import com.smartfirehub.global.exception.CodedApiException;
import org.springframework.http.HttpStatus;

/**
 * DBA 자동 생성 실패(드라이버 없음·접속 실패·권한 부족·단계 실패 포함). 422 + 고정 코드로 응답된다.
 *
 * <p>메시지에는 비밀번호·{@code IDENTIFIED BY} SQL 이 들어가면 안 된다.
 */
public class DataStoreProvisioningException extends CodedApiException {

  public static final String CODE = "DATA_STORE_PROVISIONING_FAILED";

  public DataStoreProvisioningException(String message) {
    super(HttpStatus.UNPROCESSABLE_ENTITY, CODE, message);
  }
}
