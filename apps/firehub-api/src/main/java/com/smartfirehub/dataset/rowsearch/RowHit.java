package com.smartfirehub.dataset.rowsearch;

/** 색인 엔진 검색 결과 한 건 — 원본 행 id 와 점수만(원본 조회는 호출자 몫). */
public record RowHit(long rowId, double score) {}
