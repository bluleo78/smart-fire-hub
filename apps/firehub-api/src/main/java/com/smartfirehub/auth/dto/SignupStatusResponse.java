package com.smartfirehub.auth.dto;

/** 공개 가입 열림 여부(GET /auth/signup-status). 사용자 0명일 때만 true. */
public record SignupStatusResponse(boolean open) {}
