import type { ReactNode } from 'react';

type ViewFrameProps = {
  title: string;
  subtitle?: string;
  actions?: ReactNode;
  children: ReactNode;
};

export function ViewFrame({ title, subtitle, actions, children }: ViewFrameProps) {
  return (
    <section className="view-frame command-view">
      <div className="view-header command-view-header">
        <div className="cc-view-title"><h2 tabIndex={-1}>{title}</h2>{subtitle && <p>{subtitle}</p>}</div>
        <div className="view-actions command-view-actions">{actions}</div>
      </div>
      {children}
    </section>
  );
}
