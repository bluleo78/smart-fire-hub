import axios from 'axios';
import { toast } from 'sonner';
import type { ErrorResponse } from '@/types/auth';

export function extractApiError(error: unknown, fallback: string): string {
  if (axios.isAxiosError(error) && error.response?.data) {
    const errData = error.response.data as ErrorResponse;
    return errData.message || fallback;
  }
  return fallback;
}

export function handleApiError(error: unknown, fallback: string): void {
  toast.error(extractApiError(error, fallback));
}
