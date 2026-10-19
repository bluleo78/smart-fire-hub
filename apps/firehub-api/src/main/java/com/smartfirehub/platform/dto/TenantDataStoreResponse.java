package com.smartfirehub.platform.dto;

import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.DataStoreProvisionMode;
import com.smartfirehub.dataset.storage.TenantDataStoreStatus;
import java.util.List;

/**
 * 테넌트의 데이터셋 저장소 조회 응답(WD-73). 비밀번호·암호문은 담지 않고 계정 이름만 돌려준다.
 *
 * <p>저장소 행이 없는 테넌트는 {@code backend=POSTGRESQL}, 나머지는 null·빈 목록이다.
 */
public record TenantDataStoreResponse(
    DataStoreBackend backend,
    String host,
    Integer port,
    String database,
    String schemaName,
    String tablespace,
    DataStoreProvisionMode mode,
    TenantDataStoreStatus status,
    List<String> accountUsernames) {}
