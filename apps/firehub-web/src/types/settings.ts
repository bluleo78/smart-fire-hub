export interface SettingResponse {
  key: string;
  value: string;
  description: string;
  updatedAt: string;
}

export interface UpdateSettingsRequest {
  settings: Record<string, string>;
}
