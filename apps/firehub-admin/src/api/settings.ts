import type { SettingResponse } from '../types/platform';
import { client } from './client';

export const settingsApi = {
  /** 플랫폼 기본값 전체(이메일·임베딩 문자열 키·값).
   *
   * AI 설정(`ai.*`)은 이 응답에 없다 — 워크스페이스별 설정이라 플랫폼 설정에 속하지 않는다.
   * 서버는 `ai.*` 키 쓰기를 거부한다. */
  getAll: () => client.get<SettingResponse[]>('/settings'),
  /** 부분 갱신. 변경된 키만 담아 보낸다. 성공은 204. */
  update: (settings: Record<string, string>) => client.put<void>('/settings', settings),
};
