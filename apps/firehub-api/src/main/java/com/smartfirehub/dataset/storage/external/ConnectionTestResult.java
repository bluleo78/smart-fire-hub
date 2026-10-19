package com.smartfirehub.dataset.storage.external;

import java.util.List;

/**
 * DBA 연결 테스트 결과.
 *
 * @param ok 접속 성공이고 부족한 권한이 없을 때 true
 * @param serverVersion DB 제품 버전(접속 실패 시 null)
 * @param missingPrivileges 부족한 권한. 빈 목록이면 충분, null 이면 "권한 확인 불가"
 * @param message 사용자에게 보여 줄 사유
 */
public record ConnectionTestResult(
    boolean ok, String serverVersion, List<String> missingPrivileges, String message) {}
