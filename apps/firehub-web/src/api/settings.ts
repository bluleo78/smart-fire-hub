import type { SettingResponse, UpdateSettingsRequest } from '../types/settings';
import { client } from './client';

export const settingsApi = {
  getByPrefix: (prefix: string) =>
    client.get<SettingResponse[]>('/settings', { params: { prefix } }),

  update: (data: UpdateSettingsRequest) =>
    client.put('/settings', data),
};
