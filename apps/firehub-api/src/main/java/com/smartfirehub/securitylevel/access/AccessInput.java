package com.smartfirehub.securitylevel.access;

import java.util.Set;

/**
 * 판정 입력(스펙 §4.1). hosting 은 AI 행위에서만 의미가 있고, null 은 외부로 간주한다(fail-closed).
 *
 * @param userRank 사용자 자격 rank. 역할이 하나도 없으면 {@link Clearance#NO_RANK}
 * @param onAllowlist 사용자 본인 또는 보유 역할이 데이터셋 허용 목록에 있는가
 * @param tenantAdmin 시스템 ADMIN 역할 보유 여부(admin_bypass 판정용)
 */
public record AccessInput(
    int userRank,
    boolean onAllowlist,
    boolean tenantAdmin,
    LevelPolicy level,
    DatasetAction action,
    Set<String> permissions,
    ProviderHosting hosting) {}
