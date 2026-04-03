interface DragInsertIndicatorProps {
  visible: boolean;
  indent: number; // depth level for left offset
}

export function DragInsertIndicator({ visible, indent }: DragInsertIndicatorProps) {
  if (!visible) return null;
  return (
    <div
      className="h-0.5 bg-primary rounded-full"
      style={{ marginLeft: indent * 16 }}
    />
  );
}
