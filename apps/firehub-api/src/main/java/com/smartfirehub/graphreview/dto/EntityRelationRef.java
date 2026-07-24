package com.smartfirehub.graphreview.dto;

/** 저신뢰 엔티티가 끌린 보류 관계 참조 — 승인 시 add-entity로 함께 적재된다. */
public record EntityRelationRef(String relType, String direction, String otherKey) {}
