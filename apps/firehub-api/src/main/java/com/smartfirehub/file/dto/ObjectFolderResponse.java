package com.smartfirehub.file.dto;

/**
 * 폴더 모드 목록의 하위 폴더 1건(S3 common prefix). 화면이 폴더 행을 그리고, 누르면 그 폴더로 들어가는 데 쓴다(WD-761).
 *
 * @param path 데이터셋 prefix 를 제외한 상대 경로 + 끝 '/'(예 "제품/Tibero7/") — 그대로 목록 API 의 path 로 넘길 수 있다.
 * @param name 마지막 세그먼트(예 "Tibero7") — 표시명.
 */
public record ObjectFolderResponse(String path, String name) {}
