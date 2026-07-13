import type { ReactNode } from 'react';

type ViewFrameProps = {
  title: string;
  actions?: ReactNode;
  children: ReactNode;
};

export function ViewFrame({ title, actions, children }: ViewFrameProps) {
  return (
    <section className="view-frame command-view">
      <div className="view-header command-view-header">
        <h2 tabIndex={-1}>{title}</h2>
        <div className="view-actions command-view-actions">{actions}</div>
      </div>
      {children}
    </section>
  );
}
