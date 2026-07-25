package com.smartfirehub.graphreview.dto;

/** 저신뢰 관계 기존 결정 조회 응답 — status는 approved/rejected/pending/none. */
public record RelationLookupResponse(String status) {}
