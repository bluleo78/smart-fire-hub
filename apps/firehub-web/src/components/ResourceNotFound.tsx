import type { LucideIcon } from 'lucide-react';

import { Button } from './ui/button';

/**
 * 상세 화면에 들어왔는데 대상이 없을 때(404 — 공유받은 링크의 대상이 지워진 경우 등) 화면 안에 보이는 안내 (WD-581).
 *
 * 예전엔 데이터셋·사용자·역할 상세가 토스트를 띄우고 목록으로 이동해 주소가 바뀌었다 — 토스트가 사라지면 왜 목록에 와 있는지 알 수 없었다.
 * 쿼리·차트·파이프라인 상세처럼 주소는 그대로 두고 「무엇을 찾을 수 없는지」와 「목록으로」 버튼을 화면에 남긴다.
 * 안내 문구를 제목(h1)으로 두어 스크린리더가 제목 탐색으로 바로 찾을 수 있게 한다. 디자인 가이드의 빈 상태 패턴(아이콘 + 메시지 + 액션)을 따른다.
 */
export function ResourceNotFound({
  icon: Icon,
  message,
  onBackToList,
}: {
  /** 리소스 종류를 나타내는 아이콘 — 장식이라 스크린리더에서는 숨긴다 */
  icon: LucideIcon;
  /** 「<리소스>를 찾을 수 없습니다.」 또는 서버가 준 안내 문구 */
  message: string;
  /** 「목록으로」를 눌렀을 때 해당 리소스 목록으로 이동한다 */
  onBackToList: () => void;
}) {
  return (
    <div className="flex flex-col items-center justify-center gap-4 py-20 text-center">
      <Icon className="h-12 w-12 text-muted-foreground" aria-hidden="true" />
      <h1 className="text-base font-medium text-muted-foreground">{message}</h1>
      <Button variant="outline" onClick={onBackToList}>
        목록으로
      </Button>
    </div>
  );
}
