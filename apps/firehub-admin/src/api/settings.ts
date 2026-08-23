import type { SettingResponse } from '../types/platform';
import { client } from './client';

export const settingsApi = {
  /** 18행이 온다 — `ai.session_max_tokens` 는 어떤 마이그레이션도 시드하지 않아 응답에 없다. */
  getAll: () => client.get<SettingResponse[]>('/settings'),
  /** 부분 갱신. 변경된 키만 담아 보낸다. 성공은 204. */
  update: (settings: Record<string, string>) => client.put<void>('/settings', settings),
};
