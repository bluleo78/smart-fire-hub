import type { ResolvedSettingResponse, UpdateSettingsRequest } from '../types/settings';
import { client } from './client';

export const settingsApi = {
  // 해석된 값 + overridden/tenantEditable 플래그를 함께 받는다 (P7-b).
  getByPrefix: (prefix: string) =>
    client.get<ResolvedSettingResponse[]>('/settings', { params: { prefix } }),

  update: (data: UpdateSettingsRequest) =>
    client.put('/settings', data),

  // 테넌트 오버라이드 삭제 = 플랫폼 기본값으로 복귀. 멱등(이미 상속 중이어도 204).
  clearOverride: (key: string) =>
    client.delete(`/settings/overrides/${encodeURIComponent(key)}`),

  verifyAuthStatus: () =>
    client.get<{ valid: boolean; email?: string; subscriptionType?: string }>('/ai/auth-status'),
};
