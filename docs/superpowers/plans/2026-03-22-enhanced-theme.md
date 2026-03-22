# Enhanced Theme Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Apply a refined dual-theme design system (Light + Dark) with 7 design improvements across the entire Smart Fire Hub frontend app.

**Architecture:** CSS token overhaul in `index.css` for indigo accent + domain colors, ThemeProvider wrapper in `App.tsx`, theme toggle in sidebar, micro-interactions via Tailwind utilities, glassmorphism on AI chip, sparkline/freshness components on HomePage.

**Tech Stack:** React 19, Tailwind CSS v4, shadcn/ui (new-york), next-themes, Lucide icons, Inter font (@fontsource/inter)

**Spec:** `docs/superpowers/specs/2026-03-22-enhanced-theme-design.md`

**Visual Reference:** `snapshots/enhanced-full-version.png`

---

## File Structure

### New Files
- `apps/firehub-web/src/components/layout/ThemeToggle.tsx` — Sun/Moon toggle button
- `apps/firehub-web/src/components/ui/sparkline.tsx` — Mini bar chart for stats cards
- `apps/firehub-web/src/components/ui/freshness-bar.tsx` — Data freshness progress indicator

### Modified Files
- `apps/firehub-web/package.json` — Add `@fontsource/inter`
- `apps/firehub-web/src/index.css` — Theme tokens overhaul (light/dark), domain colors, gradient backgrounds, glassmorphism utilities, micro-interaction utilities
- `apps/firehub-web/src/main.tsx` — Import Inter font
- `apps/firehub-web/src/App.tsx` — Wrap with ThemeProvider
- `apps/firehub-web/src/components/layout/AppLayout.tsx` — Sidebar active indicator, gradient background, glassmorphism sidebar (dark), logo pulse animation
- `apps/firehub-web/src/components/layout/UserNav.tsx` — Online status dot, themed avatar
- `apps/firehub-web/src/components/ai/AIStatusChip.tsx` — Replace hardcoded RGBA with CSS vars, add glassmorphism
- `apps/firehub-web/src/pages/HomePage.tsx` — Domain-colored stats, sparklines, freshness bars, hover effects
- `apps/firehub-web/src/pages/data/DatasetListPage.tsx` — Domain colors, hover effects, freshness bar
- `apps/firehub-web/src/pages/pipeline/PipelineListPage.tsx` — Domain colors, hover effects
- `apps/firehub-web/src/pages/analytics/QueryListPage.tsx` — Hover effects
- `apps/firehub-web/src/pages/analytics/ChartListPage.tsx` — Hover effects
- `apps/firehub-web/src/pages/analytics/DashboardListPage.tsx` — Hover effects
- `apps/firehub-web/src/pages/admin/UserListPage.tsx` — Hover effects
- `apps/firehub-web/src/pages/admin/RoleListPage.tsx` — Hover effects
- `apps/firehub-web/src/pages/admin/AuditLogListPage.tsx` — Hover effects
- `apps/firehub-web/src/pages/admin/SettingsPage.tsx` — Hover effects

---

## Task 1: Install Inter Font + ThemeProvider Setup

**Files:**
- Modify: `apps/firehub-web/package.json`
- Modify: `apps/firehub-web/src/main.tsx`
- Modify: `apps/firehub-web/src/App.tsx`

- [ ] **Step 1: Install @fontsource/inter**

```bash
cd apps/firehub-web && pnpm add @fontsource/inter
```

- [ ] **Step 2: Import Inter font in main.tsx**

Add at the top of `apps/firehub-web/src/main.tsx`:

```tsx
import '@fontsource/inter/400.css';
import '@fontsource/inter/500.css';
import '@fontsource/inter/600.css';
import '@fontsource/inter/700.css';
import '@fontsource/inter/800.css';
```

- [ ] **Step 3: Wrap App with ThemeProvider**

Modify `apps/firehub-web/src/App.tsx`:

```tsx
import { ThemeProvider } from 'next-themes';

// In the App function, wrap everything with ThemeProvider:
function App() {
  return (
    <ThemeProvider attribute="class" defaultTheme="system" enableSystem>
      <BrowserRouter>
        <AuthProvider>
          {/* ... existing routes ... */}
          <Toaster />
        </AuthProvider>
      </BrowserRouter>
    </ThemeProvider>
  );
}
```

- [ ] **Step 4: Verify build passes**

```bash
cd /Users/bluleo78/git/smart-fire-hub && pnpm typecheck && pnpm build
```

Expected: Both pass. Theme defaults to system preference.

- [ ] **Step 5: Commit**

```bash
git add apps/firehub-web/package.json apps/firehub-web/pnpm-lock.yaml apps/firehub-web/src/main.tsx apps/firehub-web/src/App.tsx
git commit -m "feat(web): add Inter font and ThemeProvider setup"
```

---

## Task 2: CSS Theme Tokens Overhaul

**Files:**
- Modify: `apps/firehub-web/src/index.css`

This is the core change. Replaces achromatic palette with indigo accent, adds domain colors, gradient backgrounds, glassmorphism utilities, and micro-interaction helpers.

- [ ] **Step 1: Update font family in @theme inline**

In `index.css`, add inside `@theme inline { ... }`:

```css
--font-sans: 'Inter', ui-sans-serif, system-ui, sans-serif;
```

- [ ] **Step 2: Add domain color tokens to @theme inline**

Add inside `@theme inline { ... }`:

```css
--color-pipeline: var(--pipeline);
--color-pipeline-foreground: var(--pipeline-foreground);
--color-dataset: var(--dataset);
--color-dataset-foreground: var(--dataset-foreground);
--color-dashboard-accent: var(--dashboard-accent);
--color-dashboard-accent-foreground: var(--dashboard-accent-foreground);
```

- [ ] **Step 3: Update :root (light theme) tokens**

Replace the `:root { ... }` block with:

```css
:root {
  --radius: 0.625rem;
  /* Core — off-white background with indigo tint */
  --background: oklch(0.985 0.002 264);
  --foreground: oklch(0.145 0 0);
  --card: oklch(1 0 0);
  --card-foreground: oklch(0.145 0 0);
  --popover: oklch(1 0 0);
  --popover-foreground: oklch(0.145 0 0);
  /* Primary — indigo */
  --primary: oklch(0.45 0.2 264);
  --primary-foreground: oklch(0.985 0 0);
  /* Secondary / Muted / Accent */
  --secondary: oklch(0.965 0.005 264);
  --secondary-foreground: oklch(0.25 0 0);
  --muted: oklch(0.965 0.005 264);
  --muted-foreground: oklch(0.5 0 0);
  --accent: oklch(0.955 0.01 264);
  --accent-foreground: oklch(0.25 0 0);
  /* Destructive */
  --destructive: oklch(0.577 0.245 27.325);
  /* Borders — softer */
  --border: oklch(0.94 0.005 264);
  --input: oklch(0.92 0.005 264);
  --ring: oklch(0.55 0.15 264);
  /* Charts */
  --chart-1: oklch(0.45 0.2 264);
  --chart-2: oklch(0.55 0.15 195);
  --chart-3: oklch(0.5 0.18 300);
  --chart-4: oklch(0.7 0.15 84);
  --chart-5: oklch(0.6 0.2 27);
  /* Sidebar — indigo accent */
  --sidebar: oklch(1 0 0);
  --sidebar-foreground: oklch(0.145 0 0);
  --sidebar-primary: oklch(0.45 0.2 264);
  --sidebar-primary-foreground: oklch(0.985 0 0);
  --sidebar-accent: oklch(0.955 0.015 264);
  --sidebar-accent-foreground: oklch(0.25 0 0);
  --sidebar-border: oklch(0.94 0.005 264);
  --sidebar-ring: oklch(0.55 0.15 264);
  /* Status */
  --success: oklch(0.523 0.165 149.5);
  --success-foreground: oklch(0.985 0 0);
  --success-subtle: oklch(0.95 0.05 149.5);
  --warning: oklch(0.84 0.16 84);
  --warning-foreground: oklch(0.2 0 0);
  --warning-subtle: oklch(0.97 0.04 84);
  --info: oklch(0.55 0.15 240);
  --info-foreground: oklch(0.985 0 0);
  --info-subtle: oklch(0.95 0.04 240);
  /* Domain colors */
  --pipeline: oklch(0.52 0.14 195);
  --pipeline-foreground: oklch(0.985 0 0);
  --dataset: oklch(0.45 0.2 264);
  --dataset-foreground: oklch(0.985 0 0);
  --dashboard-accent: oklch(0.48 0.2 300);
  --dashboard-accent-foreground: oklch(0.985 0 0);
}
```

- [ ] **Step 4: Update .dark tokens**

Replace the `.dark { ... }` block with:

```css
.dark {
  /* Core — deep navy */
  --background: oklch(0.13 0.015 280);
  --foreground: oklch(0.93 0 0);
  --card: oklch(1 0 0 / 3%);
  --card-foreground: oklch(0.93 0 0);
  --popover: oklch(0.18 0.015 280);
  --popover-foreground: oklch(0.93 0 0);
  /* Primary — bright indigo */
  --primary: oklch(0.65 0.2 264);
  --primary-foreground: oklch(0.985 0 0);
  /* Secondary / Muted / Accent */
  --secondary: oklch(1 0 0 / 5%);
  --secondary-foreground: oklch(0.93 0 0);
  --muted: oklch(1 0 0 / 5%);
  --muted-foreground: oklch(0.6 0 0);
  --accent: oklch(1 0 0 / 7%);
  --accent-foreground: oklch(0.93 0 0);
  /* Destructive */
  --destructive: oklch(0.704 0.191 22.216);
  /* Borders — subtle */
  --border: oklch(1 0 0 / 6%);
  --input: oklch(1 0 0 / 10%);
  --ring: oklch(0.65 0.2 264);
  /* Charts */
  --chart-1: oklch(0.65 0.2 264);
  --chart-2: oklch(0.7 0.15 195);
  --chart-3: oklch(0.65 0.2 300);
  --chart-4: oklch(0.75 0.15 84);
  --chart-5: oklch(0.7 0.2 27);
  /* Sidebar */
  --sidebar: oklch(0.14 0.02 280);
  --sidebar-foreground: oklch(0.93 0 0);
  --sidebar-primary: oklch(0.65 0.2 264);
  --sidebar-primary-foreground: oklch(0.985 0 0);
  --sidebar-accent: oklch(1 0 0 / 7%);
  --sidebar-accent-foreground: oklch(0.93 0 0);
  --sidebar-border: oklch(1 0 0 / 6%);
  --sidebar-ring: oklch(0.65 0.2 264);
  /* Status */
  --success: oklch(0.65 0.15 149.5);
  --success-foreground: oklch(0.985 0 0);
  --success-subtle: oklch(0.2 0.04 149.5);
  --warning: oklch(0.76 0.14 84);
  --warning-foreground: oklch(0.985 0 0);
  --warning-subtle: oklch(0.2 0.04 84);
  --info: oklch(0.7 0.13 240);
  --info-foreground: oklch(0.985 0 0);
  --info-subtle: oklch(0.2 0.04 240);
  /* Domain colors */
  --pipeline: oklch(0.75 0.12 195);
  --pipeline-foreground: oklch(0.985 0 0);
  --dataset: oklch(0.7 0.17 264);
  --dataset-foreground: oklch(0.985 0 0);
  --dashboard-accent: oklch(0.7 0.18 300);
  --dashboard-accent-foreground: oklch(0.985 0 0);
}
```

- [ ] **Step 5: Add utility classes after @layer base**

Append after the existing `@layer base { ... }` block:

```css
/* Background gradient */
.bg-gradient-main {
  background: radial-gradient(ellipse at top center, var(--background), var(--background));
}
:root .bg-gradient-main {
  background: radial-gradient(ellipse at top center, oklch(1 0 0), oklch(0.97 0.003 264));
}
.dark .bg-gradient-main {
  background: radial-gradient(ellipse at top center, oklch(0.16 0.02 280), oklch(0.11 0.015 280));
}

/* Card hover effect */
.card-hover {
  transition: transform 0.2s, box-shadow 0.2s, border-color 0.2s;
}
.card-hover:hover {
  transform: translateY(-2px);
}
:root .card-hover:hover {
  box-shadow: 0 4px 12px rgba(0,0,0,0.08);
}
.dark .card-hover:hover {
  box-shadow: 0 4px 16px rgba(0,0,0,0.3);
  border-color: oklch(0.65 0.2 264 / 15%);
}

/* Row hover effect */
.row-hover {
  transition: background-color 0.15s, border-color 0.15s;
}
:root .row-hover:hover {
  background-color: oklch(0.97 0.005 264);
}
.dark .row-hover:hover {
  background-color: oklch(0.65 0.2 264 / 4%);
}

/* Glassmorphism */
.glass {
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
}

/* Logo pulse */
@keyframes logo-pulse {
  0%, 100% { box-shadow: 0 3px 10px oklch(0.45 0.2 264 / 30%); }
  50% { box-shadow: 0 3px 20px oklch(0.45 0.2 264 / 50%); }
}
.dark .logo-pulse {
  animation: logo-pulse-dark 3s ease-in-out infinite;
}
:root .logo-pulse {
  animation: logo-pulse 3s ease-in-out infinite;
}
@keyframes logo-pulse-dark {
  0%, 100% { box-shadow: 0 3px 12px oklch(0.65 0.2 264 / 30%); }
  50% { box-shadow: 0 3px 24px oklch(0.65 0.2 264 / 50%); }
}

/* Tabular numbers */
.tabular-nums {
  font-variant-numeric: tabular-nums;
}

/* Active sidebar indicator */
.nav-active-indicator {
  position: relative;
}
.nav-active-indicator::before {
  content: '';
  position: absolute;
  left: 0;
  top: 25%;
  bottom: 25%;
  width: 3px;
  border-radius: 0 3px 3px 0;
  background: var(--primary);
}
.dark .nav-active-indicator::before {
  background: linear-gradient(180deg, oklch(0.65 0.2 264), oklch(0.6 0.2 300));
  box-shadow: 0 0 8px oklch(0.65 0.2 264 / 40%);
}

/* Online status dot */
.status-online {
  position: relative;
}
.status-online::after {
  content: '';
  position: absolute;
  bottom: 0;
  right: 0;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: oklch(0.7 0.2 149);
  border: 2px solid var(--background);
}
.dark .status-online::after {
  box-shadow: 0 0 6px oklch(0.7 0.2 149 / 40%);
}
```

- [ ] **Step 6: Verify build passes**

```bash
cd /Users/bluleo78/git/smart-fire-hub && pnpm typecheck && pnpm build
```

- [ ] **Step 7: Commit**

```bash
git add apps/firehub-web/src/index.css
git commit -m "feat(web): overhaul CSS theme tokens — indigo accent, domain colors, utility classes"
```

---

## Task 3: Theme Toggle Component + Sidebar Integration

**Files:**
- Create: `apps/firehub-web/src/components/layout/ThemeToggle.tsx`
- Modify: `apps/firehub-web/src/components/layout/AppLayout.tsx`

- [ ] **Step 1: Create ThemeToggle component**

Create `apps/firehub-web/src/components/layout/ThemeToggle.tsx`:

```tsx
import { Moon, Sun } from 'lucide-react';
import { useTheme } from 'next-themes';

import { cn } from '../../lib/utils';
import { Button } from '../ui/button';
import { Tooltip, TooltipContent, TooltipTrigger } from '../ui/tooltip';

interface ThemeToggleProps {
  collapsed?: boolean;
}

export function ThemeToggle({ collapsed = false }: ThemeToggleProps) {
  const { resolvedTheme, setTheme } = useTheme();

  const toggle = () => setTheme(resolvedTheme === 'dark' ? 'light' : 'dark');

  const button = (
    <Button
      variant="ghost"
      size="icon"
      onClick={toggle}
      className={cn(
        'transition-colors text-muted-foreground hover:text-foreground',
        collapsed ? 'h-8 w-8 mx-auto' : 'h-8 w-8'
      )}
      aria-label="테마 전환"
    >
      <Sun className="h-4 w-4 rotate-0 scale-100 transition-all dark:-rotate-90 dark:scale-0" />
      <Moon className="absolute h-4 w-4 rotate-90 scale-0 transition-all dark:rotate-0 dark:scale-100" />
    </Button>
  );

  if (collapsed) {
    return (
      <Tooltip>
        <TooltipTrigger asChild>{button}</TooltipTrigger>
        <TooltipContent side="right" sideOffset={8}>
          테마 전환
        </TooltipContent>
      </Tooltip>
    );
  }

  return (
    <div className="flex items-center justify-between px-3 py-1">
      <span className="text-xs text-muted-foreground">테마</span>
      {button}
    </div>
  );
}
```

- [ ] **Step 2: Integrate into AppLayout sidebar**

In `AppLayout.tsx`, import ThemeToggle:

```tsx
import { ThemeToggle } from './ThemeToggle';
```

Replace the bottom anchor section (lines ~362-365):

```tsx
{/* Bottom anchor: Theme toggle + UserNav */}
<div className="shrink-0 border-t">
  <ThemeToggle collapsed={collapsed} />
  <UserNav collapsed={collapsed} />
</div>
```

- [ ] **Step 3: Add sidebar active indicator to NavItemLink**

In `AppLayout.tsx`, modify the NavItemLink Link className (lines ~115-121):

```tsx
className={cn(
  'flex items-center rounded-md text-[13px] font-medium transition-all',
  active
    ? 'bg-accent text-accent-foreground [&_svg]:text-primary nav-active-indicator'
    : 'text-muted-foreground hover:bg-accent/50 hover:text-accent-foreground hover:scale-[1.02]',
  collapsed ? 'justify-center px-2 py-2.5 mx-1' : 'gap-3 px-3 py-1.5'
)}
```

- [ ] **Step 4: Add gradient background to main content area**

In `AppLayout.tsx`, add `bg-gradient-main` to the main content wrapper (line ~388):

```tsx
<div className="relative flex flex-1 min-h-0 bg-gradient-main">
```

- [ ] **Step 5: Add logo pulse animation**

In `AppLayout.tsx`, update the Flame icon in the sidebar header (line ~275 collapsed, ~289 expanded):

For collapsed logo button:
```tsx
<Flame className="h-5 w-5 text-primary logo-pulse rounded" />
```

For expanded logo:
```tsx
<Flame className="h-5 w-5 shrink-0 text-primary logo-pulse rounded" />
```

- [ ] **Step 6: Verify build + visual check**

```bash
cd /Users/bluleo78/git/smart-fire-hub && pnpm typecheck && pnpm build
```

Then visually verify in browser: theme toggle works, sidebar indicator shows, gradient background visible.

- [ ] **Step 7: Commit**

```bash
git add apps/firehub-web/src/components/layout/ThemeToggle.tsx apps/firehub-web/src/components/layout/AppLayout.tsx
git commit -m "feat(web): add theme toggle, sidebar active indicator, gradient background"
```

---

## Task 4: UserNav Enhancement — Online Status + Themed Avatar

**Files:**
- Modify: `apps/firehub-web/src/components/layout/UserNav.tsx`

- [ ] **Step 1: Add online status dot to avatar**

In `UserNav.tsx`, update the Avatar wrapper to include online status:

```tsx
<div className="relative status-online">
  <Avatar className={cn('shrink-0', collapsed ? 'h-7 w-7' : 'h-8 w-8')}>
    <AvatarFallback className="text-xs bg-primary/10 text-primary font-semibold">
      {getInitials(user.name || 'U')}
    </AvatarFallback>
  </Avatar>
</div>
```

- [ ] **Step 2: Verify build**

```bash
cd /Users/bluleo78/git/smart-fire-hub && pnpm typecheck
```

- [ ] **Step 3: Commit**

```bash
git add apps/firehub-web/src/components/layout/UserNav.tsx
git commit -m "feat(web): themed avatar with online status dot"
```

---

## Task 5: AIStatusChip — Glassmorphism + CSS Variables

**Files:**
- Modify: `apps/firehub-web/src/components/ai/AIStatusChip.tsx`

- [ ] **Step 1: Replace hardcoded chipStyles with CSS var-based styles**

In `AIStatusChip.tsx`, replace the `chipStyles` object to use CSS variables:

```tsx
const chipStyles: Record<ChipState, React.CSSProperties> = {
  idle: {
    background: 'oklch(from var(--primary) l c h / 15%)',
    border: '1px solid oklch(from var(--primary) l c h / 30%)',
    color: 'var(--primary)',
    backdropFilter: 'blur(12px)',
  },
  streaming: {
    background: 'oklch(from var(--primary) l c h / 25%)',
    border: '1px solid oklch(from var(--primary) l c h / 50%)',
    color: 'var(--primary)',
    boxShadow: '0 0 12px oklch(from var(--primary) l c h / 20%)',
    backdropFilter: 'blur(12px)',
  },
  thinking: {
    background: 'oklch(from var(--warning) l c h / 15%)',
    border: '1px solid oklch(from var(--warning) l c h / 30%)',
    color: 'var(--warning)',
    backdropFilter: 'blur(12px)',
  },
  error: {
    background: 'oklch(from var(--destructive) l c h / 20%)',
    border: '1px solid oklch(from var(--destructive) l c h / 40%)',
    color: 'var(--destructive)',
    backdropFilter: 'blur(12px)',
  },
  side: {
    background: 'oklch(from var(--primary) l c h / 30%)',
    border: '1px solid var(--primary)',
    color: 'var(--primary)',
    backdropFilter: 'blur(12px)',
  },
  floating: {
    background: 'oklch(from var(--primary) l c h / 30%)',
    border: '1px solid var(--primary)',
    color: 'var(--primary)',
    backdropFilter: 'blur(12px)',
  },
  fullscreen: {
    background: 'oklch(from var(--primary) l c h / 30%)',
    border: '1px solid var(--primary)',
    color: 'var(--primary)',
    backdropFilter: 'blur(12px)',
  },
};
```

Note: `oklch(from ...)` relative color syntax may not be fully supported in all browsers. If there are issues, fall back to using fixed oklch values that match the primary token:
- Light: `oklch(0.45 0.2 264 / 15%)` etc.
- Dark: Use the CSS class approach where `.dark` overrides apply.

A simpler alternative approach — use Tailwind classes instead of inline styles where possible, and keep only minimal inline overrides. Test in browser after applying.

- [ ] **Step 2: Update status dot color**

Replace hardcoded `backgroundColor: '#4ade80'` with:

```tsx
backgroundColor: 'oklch(0.7 0.2 149)',
```

- [ ] **Step 3: Verify build + visual check**

```bash
cd /Users/bluleo78/git/smart-fire-hub && pnpm typecheck && pnpm build
```

Check both themes in browser — chip should adapt to theme colors.

- [ ] **Step 4: Commit**

```bash
git add apps/firehub-web/src/components/ai/AIStatusChip.tsx
git commit -m "feat(web): AIStatusChip glassmorphism + CSS variable colors"
```

---

## Task 6: Sparkline + Freshness Bar Components

**Files:**
- Create: `apps/firehub-web/src/components/ui/sparkline.tsx`
- Create: `apps/firehub-web/src/components/ui/freshness-bar.tsx`

- [ ] **Step 1: Create Sparkline component**

Create `apps/firehub-web/src/components/ui/sparkline.tsx`:

```tsx
import { cn } from '../../lib/utils';

interface SparklineProps {
  data: number[];
  color?: 'pipeline' | 'dataset' | 'dashboard';
  className?: string;
}

export function Sparkline({ data, color = 'dataset', className }: SparklineProps) {
  const max = Math.max(...data, 1);

  const colorMap = {
    pipeline: { bar: 'bg-pipeline/20', high: 'bg-pipeline' },
    dataset: { bar: 'bg-primary/20', high: 'bg-primary' },
    dashboard: { bar: 'bg-dashboard-accent/20', high: 'bg-dashboard-accent' },
  };

  const colors = colorMap[color];
  const threshold = max * 0.75;

  return (
    <div className={cn('flex items-end gap-[2px] h-5', className)}>
      {data.map((value, i) => (
        <div
          key={i}
          className={cn(
            'w-1 rounded-sm min-h-[2px] transition-all',
            value >= threshold ? colors.high : colors.bar
          )}
          style={{ height: `${(value / max) * 100}%` }}
        />
      ))}
    </div>
  );
}
```

- [ ] **Step 2: Create FreshnessBar component**

Create `apps/firehub-web/src/components/ui/freshness-bar.tsx`:

```tsx
import { cn } from '../../lib/utils';

interface FreshnessBarProps {
  lastUpdated: string | null;
  className?: string;
}

function getFreshness(lastUpdated: string | null): { percent: number; level: 'fresh' | 'stale' | 'old' } {
  if (!lastUpdated) return { percent: 0, level: 'old' };

  const days = (Date.now() - new Date(lastUpdated).getTime()) / (1000 * 60 * 60 * 24);

  if (days <= 7) return { percent: Math.max(100 - (days / 7) * 30, 70), level: 'fresh' };
  if (days <= 14) return { percent: Math.max(60 - ((days - 7) / 7) * 30, 30), level: 'stale' };
  return { percent: Math.max(25 - ((days - 14) / 14) * 15, 10), level: 'old' };
}

export function FreshnessBar({ lastUpdated, className }: FreshnessBarProps) {
  const { percent, level } = getFreshness(lastUpdated);

  const levelColors = {
    fresh: 'bg-success',
    stale: 'bg-warning',
    old: 'bg-destructive',
  };

  return (
    <div className={cn('w-10 h-1 rounded-full bg-muted overflow-hidden', className)}>
      <div
        className={cn('h-full rounded-full transition-all', levelColors[level])}
        style={{ width: `${percent}%` }}
      />
    </div>
  );
}
```

- [ ] **Step 3: Verify build**

```bash
cd /Users/bluleo78/git/smart-fire-hub && pnpm typecheck
```

- [ ] **Step 4: Commit**

```bash
git add apps/firehub-web/src/components/ui/sparkline.tsx apps/firehub-web/src/components/ui/freshness-bar.tsx
git commit -m "feat(web): add Sparkline and FreshnessBar UI components"
```

---

## Task 7: HomePage Redesign — Domain Colors + Sparklines + Freshness

**Files:**
- Modify: `apps/firehub-web/src/pages/HomePage.tsx`

This is a larger task. Apply domain colors to stat cards, add sparklines, freshness bars to recent datasets, and hover effects to all cards.

- [ ] **Step 1: Import new components**

Add at the top of `HomePage.tsx`:

```tsx
import { FreshnessBar } from '../components/ui/freshness-bar';
import { Sparkline } from '../components/ui/sparkline';
```

- [ ] **Step 2: Add domain colors to Zone 1 stat buttons**

In the System Health Bar (Zone 1), update the pipeline and dataset summary buttons to use domain-specific text colors.

For pipeline summary button:
- Add `text-pipeline` to the pipeline label
- Use `tabular-nums` class on count numbers

For dataset summary button:
- Add `text-dataset` to the dataset label (or `text-primary` since dataset = indigo = primary)

For dashboard count:
- Add `text-dashboard-accent` to the dashboard label

- [ ] **Step 3: Add `card-hover` class to all Card components**

Add `card-hover` to the className of every `<Card>` used in HomePage — this includes the stat cards, attention items card, quick action buttons, and all Zone 4/5 cards.

Example:
```tsx
<Card className="card-hover">
```

- [ ] **Step 4: Add Sparkline to stat area**

After the summary stats in Zone 1 or as an enhancement to the stat display, add sparkline mock data (real API not available yet, use placeholder data):

```tsx
<Sparkline data={[3, 5, 2, 8, 4, 6, 9]} color="pipeline" />
```

- [ ] **Step 5: Add FreshnessBar to recent datasets section**

In the Zone 4 recent datasets list, add a FreshnessBar next to each dataset item:

```tsx
<FreshnessBar lastUpdated={ds.updatedAt} className="ml-auto" />
```

- [ ] **Step 6: Add `tabular-nums` to all numeric displays**

Add `tabular-nums` class to all count/number displays (pipeline counts, dataset counts, etc.).

- [ ] **Step 7: Verify build + visual check**

```bash
cd /Users/bluleo78/git/smart-fire-hub && pnpm typecheck && pnpm build
```

Check both themes visually.

- [ ] **Step 8: Commit**

```bash
git add apps/firehub-web/src/pages/HomePage.tsx
git commit -m "feat(web): HomePage domain colors, sparklines, freshness bars, hover effects"
```

---

## Task 8: Dataset List Page — Domain Colors + Hover Effects

**Files:**
- Modify: `apps/firehub-web/src/pages/data/DatasetListPage.tsx`

- [ ] **Step 1: Add `card-hover` to cards and `row-hover` to table rows**

Add `card-hover` to any Card components. Add `row-hover` class to table rows (`<TableRow>`).

- [ ] **Step 2: Apply domain color to dataset type badges**

Update the dataset type badge styling:
- 원본 (source): `bg-primary/10 text-primary`
- 파생 (derived): `bg-success/10 text-success`
- 임시 (temp): `bg-muted text-muted-foreground`

- [ ] **Step 3: Add `tabular-nums` to numeric columns**

Add `tabular-nums` class to row count and size columns.

- [ ] **Step 4: Verify build**

```bash
cd /Users/bluleo78/git/smart-fire-hub && pnpm typecheck
```

- [ ] **Step 5: Commit**

```bash
git add apps/firehub-web/src/pages/data/DatasetListPage.tsx
git commit -m "feat(web): DatasetListPage domain colors, hover effects"
```

---

## Task 9: Pipeline List + Analytics + Admin Pages — Hover Effects

**Files:**
- Modify: `apps/firehub-web/src/pages/pipeline/PipelineListPage.tsx`
- Modify: `apps/firehub-web/src/pages/analytics/QueryListPage.tsx`
- Modify: `apps/firehub-web/src/pages/analytics/ChartListPage.tsx`
- Modify: `apps/firehub-web/src/pages/analytics/DashboardListPage.tsx`
- Modify: `apps/firehub-web/src/pages/admin/UserListPage.tsx`
- Modify: `apps/firehub-web/src/pages/admin/RoleListPage.tsx`
- Modify: `apps/firehub-web/src/pages/admin/AuditLogListPage.tsx`
- Modify: `apps/firehub-web/src/pages/admin/SettingsPage.tsx`

- [ ] **Step 1: PipelineListPage — domain colors + hover**

- Add `card-hover` to any Card components
- Add `row-hover` to table rows
- Pipeline status badge: use `text-pipeline` for active status
- Add `tabular-nums` to step/trigger counts

- [ ] **Step 2: Analytics pages — hover effects**

For QueryListPage, ChartListPage, DashboardListPage:
- Add `card-hover` to Card components
- Add `row-hover` to table rows
- Dashboard items: use `text-dashboard-accent` where appropriate

- [ ] **Step 3: Admin pages — hover effects**

For UserListPage, RoleListPage, AuditLogListPage, SettingsPage:
- Add `card-hover` to Card components
- Add `row-hover` to table rows

- [ ] **Step 4: Verify build**

```bash
cd /Users/bluleo78/git/smart-fire-hub && pnpm typecheck && pnpm build
```

- [ ] **Step 5: Commit**

```bash
git add apps/firehub-web/src/pages/
git commit -m "feat(web): apply hover effects and domain colors across all pages"
```

---

## Task 10: Playwright Visual Verification

**Files:** None (verification only)

- [ ] **Step 1: Start dev server**

```bash
pnpm dev
```

- [ ] **Step 2: Capture Light theme screenshots**

Use Playwright to navigate to key pages and capture screenshots:
- Home (light): `snapshots/enhanced-light-home.png`
- Dataset list (light): `snapshots/enhanced-light-datasets.png`
- Pipeline list (light): `snapshots/enhanced-light-pipelines.png`

- [ ] **Step 3: Toggle to Dark theme and capture**

Click the theme toggle, then capture:
- Home (dark): `snapshots/enhanced-dark-home.png`
- Dataset list (dark): `snapshots/enhanced-dark-datasets.png`
- Pipeline list (dark): `snapshots/enhanced-dark-pipelines.png`

- [ ] **Step 4: Visual comparison**

Compare screenshots against `snapshots/enhanced-full-version.png` reference mock. Verify:
- Indigo accent visible in both themes
- Gradient backgrounds render correctly
- Sidebar active indicator shows
- Logo pulse animation works
- AI chip has glassmorphism effect
- Cards have hover elevation on interaction
- Domain colors differentiate pipeline/dataset/dashboard
- No accessibility contrast issues (text readable in both themes)

- [ ] **Step 5: Fix any issues found**

Address visual discrepancies and re-verify.

- [ ] **Step 6: Final commit**

```bash
git add snapshots/
git commit -m "feat(web): enhanced theme visual verification screenshots"
```

---

## Task Dependencies

```
Task 1 (Font + ThemeProvider) ──┐
                                 ├── Task 3 (ThemeToggle + Sidebar)
Task 2 (CSS Tokens) ────────────┘         │
                                           ├── Task 7 (HomePage)
Task 4 (UserNav) ─────────────────────────┤
Task 5 (AIStatusChip) ───────────────────┤
Task 6 (Sparkline + FreshnessBar) ────────┤
                                           ├── Task 8 (DatasetList)
                                           ├── Task 9 (All other pages)
                                           └── Task 10 (Verification)
```

**Parallel opportunities:**
- Tasks 1 + 2 can run in parallel (independent foundation)
- Tasks 4 + 5 + 6 can run in parallel after Tasks 1+2 complete
- Tasks 7 + 8 + 9 can run in parallel after Task 6 completes
- Task 10 runs last (requires all others complete)
