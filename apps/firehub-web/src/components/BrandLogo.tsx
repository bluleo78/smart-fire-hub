import { Flame } from 'lucide-react';

import { useBranding } from '@/hooks/useBranding';
import { cn } from '@/lib/utils';

/**
 * 브랜드 로고.
 * 런타임 설정에 logoUrl이 있으면 이미지를, 없으면 기본 아이콘(Flame)을 렌더링한다.
 * 화이트라벨 사이트가 자체 로고를 쓰되, 미설정 시 기존 모양(Flame)을 그대로 유지한다.
 */
export function BrandLogo({ className }: { className?: string }) {
  const { logoUrl, brandName } = useBranding();

  if (logoUrl) {
    return <img src={logoUrl} alt={brandName} className={cn('object-contain', className)} />;
  }

  return <Flame className={cn('text-primary logo-pulse rounded', className)} />;
}
