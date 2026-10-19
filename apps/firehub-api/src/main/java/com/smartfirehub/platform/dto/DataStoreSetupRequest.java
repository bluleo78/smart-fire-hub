package com.smartfirehub.platform.dto;

import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.DataStoreProvisionMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 테넌트 생성 시 고르는 데이터셋 저장소(WD-73).
 *
 * <p>host·database·tablespace 는 JDBC URL·DDL 에 그대로 들어가므로 정규식 밖의 값은 400 으로 거른다. 외부 백엔드일 때 필수인
 * 필드(host, port, database, mode, DBA_AUTO 의 dba)는 백엔드에 따라 달라 여기서는 형식만 보고, 존재 여부는 서비스가 검증한다.
 *
 * @param tablespace 선택. 비면 {@code USR}
 * @param mode DBA_AUTO 는 DBA 자격으로 즉시 생성, SCRIPT 는 운영자가 스크립트를 직접 실행
 * @param dba DBA_AUTO 일 때만 필요
 */
public record DataStoreSetupRequest(
    @NotNull DataStoreBackend backend,
    @Pattern(regexp = HOST_REGEX, message = "host 형식이 올바르지 않습니다") String host,
    @Min(1) @Max(65535) Integer port,
    @Pattern(regexp = DATABASE_REGEX, message = "database 형식이 올바르지 않습니다") String database,
    @Pattern(regexp = TABLESPACE_REGEX, message = "tablespace 형식이 올바르지 않습니다") String tablespace,
    DataStoreProvisionMode mode,
    @Valid DbaCredentials dba) {
  /** host 허용 문자. 연결 테스트 요청도 같은 값을 쓴다. */
  public static final String HOST_REGEX = "^[A-Za-z0-9.-]{1,255}$";

  /** database(SID) 허용 문자. 연결 테스트 요청도 같은 값을 쓴다. */
  public static final String DATABASE_REGEX = "^[A-Za-z0-9_$#.-]{1,128}$";

  /**
   * tablespace 허용 문자. 비어 있으면 {@code USR} 을 쓰므로 빈 문자열도 받는다. DDL 직전의 최종 검사는 {@code TiberoAccountPlan}
   * 이 따로 한다(재발급 경로는 이 DTO 를 거치지 않는다).
   */
  public static final String TABLESPACE_REGEX = "^([A-Za-z][A-Za-z0-9_$#]{0,29})?$";
}
