package com.smartfirehub.platform.dto;

import com.smartfirehub.dataset.storage.DataStoreBackend;

/**
 * 테넌트 생성 화면이 보여 줄 저장소 백엔드 선택지(WD-73).
 *
 * @param available 기능 플래그 등으로 지금 고를 수 있는지. false 면 화면에서 비활성으로 보여 준다
 */
public record DataStoreBackendOption(DataStoreBackend backend, boolean available) {}
