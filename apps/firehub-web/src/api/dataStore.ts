import type { DataStoreCapabilities } from '../types/dataset';
import { client } from './client';

/** 현재 테넌트 저장소가 지원하는 기능 조회 — 인증된 모든 사용자 가능. */
export async function getDataStoreCapabilities(): Promise<DataStoreCapabilities> {
  const { data } = await client.get<DataStoreCapabilities>('/data-store/capabilities');
  return data;
}
