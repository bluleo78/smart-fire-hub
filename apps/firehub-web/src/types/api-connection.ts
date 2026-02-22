export interface ApiConnectionResponse {
  id: number;
  name: string;
  description: string | null;
  authType: 'API_KEY' | 'BEARER';
  maskedAuthConfig: Record<string, string>;
  createdBy: number;
  createdAt: string;
  updatedAt: string;
}

export interface CreateApiConnectionRequest {
  name: string;
  description?: string;
  authType: 'API_KEY' | 'BEARER';
  authConfig: Record<string, string>;
}

export interface UpdateApiConnectionRequest {
  name?: string;
  description?: string;
  authType?: 'API_KEY' | 'BEARER';
  authConfig?: Record<string, string>;
}
