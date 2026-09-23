import { Lock } from 'lucide-react';
import type { ReactNode } from 'react';

import { Badge } from '../../components/ui/badge';
import { InlineBanner } from '../../components/ui/inline-banner';
import { Label } from '../../components/ui/label';

/**
 * 플랫폼 전용(워크스페이스 편집 불가) 필드의 잠금 배지 — 지금은 임베딩 탭 전용이다.
 *
 * 잠금은 색이 아니라 아이콘+텍스트+비활성 입력+고정 안내문 네 겹으로 전달해
 * `10-accessibility.md` 의 "색상 단독 전달 금지"를 충족한다.
 *
 * 예전에는 SMTP 탭 때문에 "플랫폼 값 사용 중/우리 조직 값 적용 중" 배지도 여기 있었다. SMTP 가
 * 워크스페이스 전용이 되면서(#712) 두 값이 섞일 일이 없어져 잠금 배지 하나만 남겼다.
 */
export function PlatformLockedBadge() {
  return (
    <Badge variant="outline" aria-label="플랫폼 전용: 이 테넌트에서 편집할 수 없음">
      <Lock className="h-3 w-3" /> 플랫폼 전용
    </Badge>
  );
}

/**
 * 라벨 + (잠금 배지 | 힌트 배지) 한 줄.
 * `locked` 는 임베딩 탭용이고, AI 탭의 동작 설정은 잠금 대신 `badge`(기본값 힌트)만 넘긴다 —
 * 같은 라벨 줄 레이아웃을 두 벌 두지 않기 위해서다.
 */
export function SettingFieldLabel({
  htmlFor,
  locked,
  badge,
  children,
}: {
  htmlFor: string;
  locked?: boolean;
  badge?: ReactNode;
  children: ReactNode;
}) {
  return (
    <div className="flex flex-wrap items-center gap-2">
      <Label htmlFor={htmlFor}>{children}</Label>
      {locked && <PlatformLockedBadge />}
      {badge}
    </div>
  );
}

/**
 * 잠긴 필드 아래에 붙는 고정 안내문.
 * 툴팁이 아니라 정적 텍스트인 이유: 호버할 수 없는 사용자와 스크린리더도 잠금 사유를 알아야 한다.
 */
export function PlatformLockedNote() {
  return <p className="text-sm text-muted-foreground">플랫폼 운영자만 변경할 수 있는 항목입니다.</p>;
}

/**
 * 탭 전체가 플랫폼 전용일 때(임베딩 4/4) 쓰는 배너.
 * 탭 상단과 하단(원래 저장 버튼 자리) 두 곳에 배치해, 스크롤 위치와 무관하게 편집 불가를 알린다.
 *
 * "다른 화면에서 변경하세요"/"관리자에게 문의" 류 문구는 넣지 않는다 — 운영자 전용 화면도,
 * 문의 경로도 아직 정의되어 있지 않아 없는 기능을 가리키게 된다.
 */
export function PlatformLockedBanner({ children }: { children: ReactNode }) {
  return (
    <InlineBanner variant="info" icon={<Lock />} title="플랫폼 전용 설정">
      {children}
    </InlineBanner>
  );
}
