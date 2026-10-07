package com.smartfirehub.securitylevel.dto;

/** 현재 사용자의 열람 자격. 등급 변경 다이얼로그가 "본인 자격보다 높은 등급" 항목을 비활성화하는 데 쓴다. */
public record MyClearanceResponse(Integer rank, Long levelId) {}
