package com.smartfirehub.graphreview.dto;

/** 저신뢰 엔티티 기존 결정 조회 응답 — status는 approved/rejected/pending/none(결정 없음). */
public record EntityLookupResponse(String status) {}
