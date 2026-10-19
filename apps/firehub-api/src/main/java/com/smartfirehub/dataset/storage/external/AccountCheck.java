package com.smartfirehub.dataset.storage.external;

/** 계정 하나의 접속 검증 결과. 메시지에는 비밀번호가 없다. */
public record AccountCheck(String username, boolean ok, String message) {}
