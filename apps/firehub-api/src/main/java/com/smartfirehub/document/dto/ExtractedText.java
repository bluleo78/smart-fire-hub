package com.smartfirehub.document.dto;

/** 문서에서 추출한 텍스트와 메타. pageCount는 PDF 등 페이지 개념이 있는 포맷만 채워지고 그 외는 null. */
public record ExtractedText(String text, Integer pageCount) {}
