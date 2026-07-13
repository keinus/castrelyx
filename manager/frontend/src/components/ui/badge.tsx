import * as React from 'react';
import { cva, type VariantProps } from 'class-variance-authority';
import { cn } from '@/lib/utils';

const badgeVariants = cva(
  'inline-flex items-center gap-1 rounded-[4px] border px-1.5 py-0.5 text-[10px] font-semibold leading-none tracking-[0.03em] transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:pointer-events-none disabled:opacity-50 [&_svg]:pointer-events-none [&_svg]:size-3 [&_svg]:shrink-0',
  {
    variants: {
      variant: {
        default: 'border-transparent bg-primary text-primary-foreground',
        secondary: 'border-border bg-secondary text-secondary-foreground',
        destructive: 'border-destructive bg-destructive text-destructive-foreground',
        outline: 'border-border bg-background text-foreground',
        success: 'border-[var(--status-green)]/30 bg-[var(--status-green)]/10 text-[var(--status-green)]',
        warning: 'border-[var(--status-amber)]/30 bg-[var(--status-amber)]/10 text-[var(--status-amber)]',
        critical: 'border-[var(--status-red)]/30 bg-[var(--status-red)]/10 text-[var(--status-red)]',
        muted: 'border-[var(--status-muted)]/30 bg-[var(--status-muted)]/10 text-[var(--status-muted)]'
      }
    },
    defaultVariants: {
      variant: 'default'
    }
  }
);

function Badge({ className, variant, ...props }: React.ComponentProps<'span'> & VariantProps<typeof badgeVariants>) {
  return <span data-slot="badge" className={cn(badgeVariants({ variant }), className)} {...props} />;
}

export { Badge, badgeVariants };
