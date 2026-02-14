export interface SignupRequest {
  username: string;
  password: string;
  name: string;
  email?: string;
}

export interface LoginRequest {
  username: string;
  password: string;
}

export interface TokenResponse {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
}

export interface UserResponse {
  id: number;
  username: string;
  email: string | null;
  name: string;
  isActive: boolean;
  createdAt: string;
}

export interface ErrorResponse {
  status: number;
  error: string;
  message: string;
  errors?: Record<string, string>;
}
