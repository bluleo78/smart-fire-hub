import { Eye, EyeOff } from 'lucide-react';
import * as React from 'react';

import { cn } from '@/lib/utils';

import { Input } from './input';

/**
 * 비밀번호 표시/숨김 토글이 포함된 입력 컴포넌트.
 * - 우측에 Eye/EyeOff 아이콘 버튼을 absolute positioning으로 배치하여 input 내부에 겹쳐 표시한다.
 * - aria-label "비밀번호 보기"/"비밀번호 숨기기" 로 스크린리더 접근성 확보.
 * - autoComplete 등 표준 input 속성을 그대로 forwarding 한다 (브라우저 비밀번호 매니저 호환).
 * - 토글 버튼은 type="button"으로 폼 submit 차단.
 */
function PasswordInput({
  className,
  ...props
}: Omit<React.ComponentProps<'input'>, 'type'>) {
  const [visible, setVisible] = React.useState(false);

  return (
    <div className="relative">
      <Input
        type={visible ? 'text' : 'password'}
        // 우측 아이콘 영역만큼 padding을 추가하여 텍스트가 아이콘과 겹치지 않도록 함
        className={cn('pr-10', className)}
        {...props}
      />
      <button
        type="button"
        onClick={() => setVisible((v) => !v)}
        aria-label={visible ? '비밀번호 숨기기' : '비밀번호 보기'}
        // 클릭 영역을 충분히 확보 (44x36) — 모바일 탭 친화적
        className="absolute inset-y-0 right-0 flex items-center justify-center px-3 text-muted-foreground hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50 rounded-r-md"
        tabIndex={-1}
      >
        {visible ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
      </button>
    </div>
  );
}

export { PasswordInput };
