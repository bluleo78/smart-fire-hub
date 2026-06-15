import { Upload } from 'lucide-react';
import { useCallback,useRef, useState } from 'react';
import { toast } from 'sonner';

import { cn } from '../../../lib/utils';

interface FileUploadZoneProps {
  onFileSelect: (file: File) => void;
  accept?: string;
  disabled?: boolean;
  /** 허용되지 않은 파일 거부 시 표시할 메시지. accept 형식에 맞춰 호출부에서 지정. */
  rejectionMessage?: string;
  /** 드롭존 안에 표시할 안내 문구. 미지정 시 기본값(CSV/Excel) 사용. */
  promptText?: string;
}

/**
 * accept prop(예: ".csv,.xlsx,text/csv")을 파싱하여 파일 허용 여부를 반환한다.
 * - "." 으로 시작하는 항목은 확장자 비교, 나머지는 MIME 타입 비교.
 * - file.type 이 빈 문자열(드래그 시 Windows 등에서 발생)인 경우에도 확장자로 커버한다.
 */
function isFileAccepted(file: File, accept: string): boolean {
  const tokens = accept.split(',').map((s) => s.trim().toLowerCase()).filter(Boolean);
  const fileName = file.name.toLowerCase();
  const fileType = file.type.toLowerCase();
  return tokens.some((token) => {
    if (token.startsWith('.')) {
      return fileName.endsWith(token);
    }
    return fileType === token;
  });
}

export function FileUploadZone({
  onFileSelect,
  accept = '.csv,.xlsx',
  disabled,
  rejectionMessage = 'CSV 또는 XLSX 파일만 지원합니다.',
  promptText = 'CSV 또는 Excel 파일을 드래그하세요',
}: FileUploadZoneProps) {
  const [isDragOver, setIsDragOver] = useState(false);
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  const handleDrop = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    setIsDragOver(false);
    if (disabled) return;
    const file = e.dataTransfer.files[0];
    if (file) {
      // 드래그 앤 드롭은 <input accept> 속성이 적용되지 않으므로 직접 검증한다
      if (!isFileAccepted(file, accept)) {
        toast.error(rejectionMessage);
        return;
      }
      setSelectedFile(file);
      onFileSelect(file);
    }
  }, [onFileSelect, disabled, accept, rejectionMessage]);

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (file) {
      // 파일 선택 방식에서도 동일하게 검증 (input accept 는 힌트일 뿐, 강제하지 않음)
      if (!isFileAccepted(file, accept)) {
        toast.error(rejectionMessage);
        // input 을 초기화하여 같은 잘못된 파일을 다시 선택해도 onChange 가 발생하도록 한다
        e.target.value = '';
        return;
      }
      setSelectedFile(file);
      onFileSelect(file);
    }
  };

  /**
   * 키보드 접근성: Enter 또는 Space 키 입력 시 파일 선택 다이얼로그를 열도록 한다.
   * WCAG 2.1 SC 2.1.1 (Keyboard) 준수.
   */
  const handleKeyDown = (e: React.KeyboardEvent<HTMLDivElement>) => {
    if ((e.key === 'Enter' || e.key === ' ') && !disabled) {
      e.preventDefault();
      inputRef.current?.click();
    }
  };

  return (
    <div
      tabIndex={disabled ? -1 : 0}
      role="button"
      aria-label="파일 업로드 영역, Enter 또는 Space로 파일 선택"
      onClick={() => !disabled && inputRef.current?.click()}
      onKeyDown={handleKeyDown}
      onDragOver={(e) => { e.preventDefault(); setIsDragOver(true); }}
      onDragLeave={() => setIsDragOver(false)}
      onDrop={handleDrop}
      className={cn(
        'flex flex-col items-center justify-center rounded-lg border-2 border-dashed p-8 transition-colors cursor-pointer',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2',
        isDragOver ? 'border-primary bg-primary/5' : 'border-muted-foreground/25',
        disabled && 'opacity-50 cursor-not-allowed'
      )}
    >
      <Upload className="h-8 w-8 text-muted-foreground mb-2" />
      {selectedFile ? (
        <p className="text-sm font-medium">{selectedFile.name} ({formatFileSize(selectedFile.size)})</p>
      ) : (
        <>
          <p className="text-sm font-medium">{promptText}</p>
          <p className="text-xs text-muted-foreground mt-1">또는 클릭하여 파일 선택</p>
        </>
      )}
      <input ref={inputRef} type="file" accept={accept} onChange={handleFileChange} className="hidden" />
    </div>
  );
}

function formatFileSize(bytes: number): string {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
}
