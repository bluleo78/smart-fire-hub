import { ShieldCheck } from 'lucide-react';

import { PageHeading } from '@/components/PageHeading';

import { Tabs, TabsContent, TabsList, TabsTrigger } from '../../../components/ui/tabs';
import SecuritySettingsTab from './SecuritySettingsTab';

/**
 * ADMIN 이 아닌 security:settings 보유자의 설정 화면 — 기존 설정 페이지는 AI·SMTP·임베딩 훅이 마운트 즉시 ADMIN 전용 API 를
 * 부르므로(403·토스트) 그 페이지를 재사용하지 않고 같은 껍데기로 보안 탭만 그린다.
 */
export default function SecurityOnlySettingsPage() {
  return (
    <div className="max-w-2xl mx-auto space-y-6 p-6">
      <PageHeading>설정</PageHeading>
      <Tabs defaultValue="security">
        <TabsList>
          <TabsTrigger value="security">
            <ShieldCheck className="h-4 w-4" />
            데이터 보안
          </TabsTrigger>
        </TabsList>
        <TabsContent value="security">
          <SecuritySettingsTab />
        </TabsContent>
      </Tabs>
    </div>
  );
}
