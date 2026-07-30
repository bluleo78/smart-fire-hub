import { Flame } from 'lucide-react';
import { useState } from 'react';

import { useBranding } from '@/hooks/useBranding';
import { cn } from '@/lib/utils';

/**
 * 브랜드 로고.
 * 런타임 설정에 logoUrl이 있으면 이미지를, 없으면 기본 아이콘(Flame)을 렌더링한다.
 * 화이트라벨 사이트가 자체 로고를 쓰되, 미설정 시 기존 모양(Flame)을 그대로 유지한다.
 *
 * 로고 URL이 404 등으로 로드에 실패하면 깨진 이미지를 남기지 않고 기본 아이콘으로 폴백한다(#325).
 * config.js는 사이트별 파일로 마운트되므로 경로 오타·파일 누락이 현실적인 실패다 —
 * 배포 오설정을 화면 깨짐이 아니라 기본 브랜딩으로 흡수한다.
 */
export function BrandLogo({ className }: { className?: string }) {
  const { logoUrl, brandName } = useBranding();
  // 실패 여부를 boolean이 아니라 "실패한 URL"로 들고 있으면, logoUrl이 바뀔 때
  // 리셋 이펙트 없이도 새 URL을 자연스럽게 다시 시도하게 된다.
  const [failedUrl, setFailedUrl] = useState<string | null>(null);

  if (logoUrl && failedUrl !== logoUrl) {
    return (
      <img
        src={logoUrl}
        alt={brandName}
        className={cn('object-contain', className)}
        onError={() => setFailedUrl(logoUrl)}
      />
    );
  }

  return <Flame className={cn('text-primary logo-pulse rounded', className)} />;
}
