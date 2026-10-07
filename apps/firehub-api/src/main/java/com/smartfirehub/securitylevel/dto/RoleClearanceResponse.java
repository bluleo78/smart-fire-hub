package com.smartfirehub.securitylevel.dto;

/** 역할 편집 화면 「데이터 열람 등급」 Card 데이터. fixed=true 면 시스템 ADMIN(최상위 고정, 읽기 전용). */
public record RoleClearanceResponse(
    Long roleId, Long securityLevelId, long userCount, boolean fixed) {}
