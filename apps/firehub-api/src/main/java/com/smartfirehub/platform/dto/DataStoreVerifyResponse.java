package com.smartfirehub.platform.dto;

import com.smartfirehub.dataset.storage.TenantDataStoreStatus;
import com.smartfirehub.dataset.storage.external.AccountCheck;
import java.util.List;

/**
 * 저장소 계정 검증 결과(WD-73).
 *
 * @param status 검증 후 저장소 상태. 전부 성공하면 READY, 실패가 있으면 기존 상태를 유지한다
 * @param result 검증 결과 READY / FAILED / PASSWORDS_CHANGED(확인 중 비밀번호가 재발급됨). 감사 기록과 같은 값
 * @param checks 계정별 접속 결과
 */
public record DataStoreVerifyResponse(
    TenantDataStoreStatus status, String result, List<AccountCheck> checks) {}
