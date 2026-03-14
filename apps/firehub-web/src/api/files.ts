import { client } from './client';

export interface UploadedFile {
  id: number;
  originalName: string;
  mimeType: string;
  fileSize: number;
  fileCategory: 'IMAGE' | 'PDF' | 'TEXT' | 'DATA';
  createdAt: string;
}

export async function uploadFiles(files: File[]): Promise<UploadedFile[]> {
  const formData = new FormData();
  files.forEach((file) => formData.append('files', file));
  const { data } = await client.post('/files', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  });
  return data;
}
