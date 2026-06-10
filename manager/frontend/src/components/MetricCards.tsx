type MetricCardsProps = {
  items: [string, string][];
};

export function MetricCards({ items }: MetricCardsProps) {
  return (
    <div className="metric-grid">
      {items.map(([label, value]) => (
        <section className="metric-card" key={label}>
          <span>{label}</span>
          <strong>{value}</strong>
        </section>
      ))}
    </div>
  );
}
