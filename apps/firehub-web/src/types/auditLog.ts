export interface AuditLogResponse {
  id: number;
  userId: number | null;
  username: string;
  actionType: string;
  resource: string;
  resourceId: string | null;
  description: string | null;
  actionTime: string;
  ipAddress: string | null;
  userAgent: string | null;
  result: string;
  errorMessage: string | null;
  metadata: Record<string, unknown> | null;
}
