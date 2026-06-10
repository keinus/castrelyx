import type { ReactNode } from 'react';

type ViewFrameProps = {
  title: string;
  actions?: ReactNode;
  children: ReactNode;
};

export function ViewFrame({ title, actions, children }: ViewFrameProps) {
  return (
    <section className="view-frame">
      <div className="view-header">
        <h2>{title}</h2>
        <div className="view-actions">{actions}</div>
      </div>
      {children}
    </section>
  );
}
