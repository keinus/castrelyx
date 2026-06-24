import * as React from 'react';
import { cn } from '@/lib/utils';

function Progress({ className, value = 0, ...props }: React.ComponentProps<'div'> & { value?: number | null }) {
  const width = Math.max(0, Math.min(100, Number.isFinite(value ?? 0) ? Number(value) : 0));
  return (
    <div data-slot="progress" className={cn('relative h-2 w-full overflow-hidden rounded-full bg-secondary', className)} {...props}>
      <div className="h-full w-full flex-1 bg-primary transition-all" style={{ transform: `translateX(-${100 - width}%)` }} />
    </div>
  );
}

export { Progress };
