package com.smartfirehub.platform.dto;

import com.smartfirehub.dataset.storage.DataStoreBackend;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 테넌트 생성 전 DBA 연결 테스트 요청(WD-73, R7). 접속 대상은 JDBC URL 에 그대로 들어가므로 {@link DataStoreSetupRequest} 와 같은
 * 정규식으로 거른다.
 *
 * <p>비밀번호는 {@link DbaCredentials#toString()} 이 가린다.
 */
public record DataStoreConnectionTestRequest(
    @NotNull DataStoreBackend backend,
    @NotNull @Pattern(regexp = DataStoreSetupRequest.HOST_REGEX, message = "host 형식이 올바르지 않습니다")
        String host,
    @NotNull @Min(1) @Max(65535) Integer port,
    @NotNull
        @Pattern(regexp = DataStoreSetupRequest.DATABASE_REGEX, message = "database 형식이 올바르지 않습니다")
        String database,
    @NotNull @Valid DbaCredentials credentials) {}
