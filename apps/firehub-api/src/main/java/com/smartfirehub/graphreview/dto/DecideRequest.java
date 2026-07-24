package com.smartfirehub.graphreview.dto;

/** 승인 요청 본문 — 속성 정규화 타입은 correctedValue(사람이 입력한 정정 정규화값)를 담는다. 동의어는 무시. */
public record DecideRequest(String correctedValue) {}
