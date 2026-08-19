export interface SettingResponse {
  key: string;
  value: string;
  description: string;
  updatedAt: string;
}

/**
 * 테넌트 설정 화면용 "해석된" 설정 1건 — 백엔드 `ResolvedSettingResponse` DTO와 1:1 대응.
 * `GET /settings?prefix=...` 가 이 형태로 응답한다.
 *
 * - `value`: 테넌트 오버라이드가 있으면 그 값, 없으면 플랫폼 값. 비밀 키는 `****` 로 마스킹된다.
 * - `overridden`: 지금 보이는 값이 테넌트 오버라이드에서 왔는지.
 * - `tenantEditable`: 이 키를 테넌트가 바꿀 수 있는지(오버라이드 존재 여부와 무관한 키 고유 성질).
 *
 * `value`/`description`/`updatedAt` 이 null 일 수 있는 이유: 플랫폼 시드 행 없이 오버라이드만
 * 존재하는 키는 설명·갱신시각을 줄 시스템 설정 행이 없다.
 */
export interface ResolvedSettingResponse {
  key: string;
  value: string | null;
  description: string | null;
  updatedAt: string | null;
  overridden: boolean;
  tenantEditable: boolean;
}

export interface UpdateSettingsRequest {
  settings: Record<string, string>;
}
