package com.smartfirehub.dataset.search;

/** 데이터셋 메타(이름/설명/컬럼/태그/카테고리) 변경 알림. */
public record DatasetChangedEvent(long datasetId) {}
