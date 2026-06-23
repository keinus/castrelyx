import * as React from 'react';
import * as RechartsPrimitive from 'recharts';
import { cn } from '@/lib/utils';

export type ChartConfig = {
  [key: string]: {
    label?: React.ReactNode;
    color?: string;
  };
};

type ChartContextProps = {
  config: ChartConfig;
};

const ChartContext = React.createContext<ChartContextProps | null>(null);

function useChart() {
  const context = React.useContext(ChartContext);
  if (!context) {
    throw new Error('useChart must be used within a <ChartContainer />');
  }
  return context;
}

function ChartContainer({
  id,
  className,
  children,
  config,
  ...props
}: React.ComponentProps<'div'> & {
  config: ChartConfig;
  children: React.ComponentProps<typeof RechartsPrimitive.ResponsiveContainer>['children'];
}) {
  const uniqueId = React.useId();
  const containerRef = React.useRef<HTMLDivElement>(null);
  const [ready, setReady] = React.useState(false);
  const chartId = `chart-${id ?? uniqueId.replace(/:/g, '')}`;
  const style = Object.fromEntries(
    Object.entries(config)
      .filter(([, value]) => value.color)
      .map(([key, value]) => [`--color-${key}`, value.color])
  ) as React.CSSProperties;

  React.useEffect(() => {
    const element = containerRef.current;
    if (!element) {
      return;
    }

    const updateReady = () => {
      const rect = element.getBoundingClientRect();
      setReady(rect.width > 0 && rect.height > 0);
    };

    updateReady();
    if (typeof ResizeObserver === 'undefined') {
      const frame = window.requestAnimationFrame(updateReady);
      return () => window.cancelAnimationFrame(frame);
    }

    const observer = new ResizeObserver(updateReady);
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  return (
    <ChartContext.Provider value={{ config }}>
      <div
        ref={containerRef}
        data-slot="chart"
        data-chart={chartId}
        className={cn(
          'flex aspect-video justify-center text-xs [&_.recharts-cartesian-axis-tick_text]:fill-muted-foreground [&_.recharts-grid_line]:stroke-border/70 [&_.recharts-legend-item-text]:text-muted-foreground [&_.recharts-tooltip-cursor]:stroke-border',
          className
        )}
        style={{ ...style, ...props.style }}
        {...props}
      >
        {ready ? (
          <RechartsPrimitive.ResponsiveContainer initialDimension={{ width: 1, height: 1 }} minWidth={1} minHeight={1}>
            {children}
          </RechartsPrimitive.ResponsiveContainer>
        ) : null}
      </div>
    </ChartContext.Provider>
  );
}

const ChartTooltip = RechartsPrimitive.Tooltip;
const ChartLegend = RechartsPrimitive.Legend;

type ChartTooltipPayloadItem = {
  color?: string;
  dataKey?: string | number;
  name?: string | number;
  value?: unknown;
  payload?: Record<string, unknown>;
};

function ChartTooltipContent({
  active,
  payload,
  className,
  label,
  labelFormatter,
  formatter,
  hideLabel = false,
  hideIndicator = false,
  nameKey,
  labelKey
}: {
  active?: boolean;
  payload?: ChartTooltipPayloadItem[];
  className?: string;
  label?: unknown;
  labelFormatter?: (label: unknown, payload: ChartTooltipPayloadItem[]) => React.ReactNode;
  formatter?: (value: unknown, name: unknown, item: ChartTooltipPayloadItem, index: number, payload: ChartTooltipPayloadItem[]) => React.ReactNode;
  hideLabel?: boolean;
  hideIndicator?: boolean;
  nameKey?: string;
  labelKey?: string;
}) {
  const { config } = useChart();

  if (!active || !payload?.length) {
    return null;
  }

  const [firstItem] = payload;
  const tooltipLabelKey = `${labelKey ?? firstItem?.dataKey ?? firstItem?.name ?? 'value'}`;
  const tooltipLabelConfig = getPayloadConfigFromPayload(config, firstItem, tooltipLabelKey);
  const tooltipLabelValue = !labelKey && typeof label === 'string'
    ? config[label as keyof typeof config]?.label ?? label
    : tooltipLabelConfig?.label;
  const tooltipLabel = hideLabel || !tooltipLabelValue
    ? null
    : <div className="font-medium">{labelFormatter ? labelFormatter(tooltipLabelValue, payload) : tooltipLabelValue}</div>;

  return (
    <div className={cn('grid min-w-32 gap-1.5 rounded-lg border bg-background px-2.5 py-2 text-xs shadow-xl', className)}>
      {tooltipLabel}
      <div className="grid gap-1.5">
        {payload.map((item, index) => {
          const key = `${nameKey ?? item.name ?? item.dataKey ?? 'value'}`;
          const itemConfig = getPayloadConfigFromPayload(config, item, key);
          const color = String(item.color ?? item.payload?.fill ?? itemConfig?.color ?? 'currentColor');
          return (
            <div key={String(item.dataKey)} className="flex min-w-0 items-center gap-2">
              {!hideIndicator && <span className="h-2.5 w-2.5 shrink-0 rounded-sm" style={{ backgroundColor: color }} />}
              <div className="flex min-w-0 flex-1 items-center justify-between gap-2">
                <span className="truncate text-muted-foreground">{itemConfig?.label ?? item.name}</span>
                <span className="font-mono font-medium tabular-nums text-foreground">
                  {formatter ? formatter(item.value, item.name, item, index, payload) : String(item.value ?? '-')}
                </span>
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}

function ChartLegendContent({
  className,
  payload,
  nameKey
}: React.ComponentProps<'div'> & {
    payload?: Array<{
      color?: string;
      dataKey?: string | number;
      value?: string | number;
    }>;
    nameKey?: string;
  }) {
  const { config } = useChart();
  if (!payload?.length) {
    return null;
  }
  return (
    <div className={cn('flex items-center justify-center gap-4', className)}>
      {payload.map((item) => {
        const key = `${nameKey ?? item.dataKey ?? 'value'}`;
        const itemConfig = getPayloadConfigFromPayload(config, item, key);
        return (
          <div key={String(item.value)} className="flex items-center gap-1.5">
            <span className="h-2.5 w-2.5 rounded-sm" style={{ backgroundColor: item.color }} />
            {itemConfig?.label ?? item.value}
          </div>
        );
      })}
    </div>
  );
}

function getPayloadConfigFromPayload(config: ChartConfig, payload: unknown, key: string) {
  if (typeof payload !== 'object' || payload === null) {
    return config[key];
  }
  const maybePayload = payload as {
    payload?: Record<string, unknown>;
    dataKey?: string | number;
    name?: string | number;
  };
  const configKey = String(
    maybePayload.payload?.[key] ??
      maybePayload.dataKey ??
      maybePayload.name ??
      key
  );
  return config[configKey] ?? config[key];
}

export { ChartContainer, ChartTooltip, ChartTooltipContent, ChartLegend, ChartLegendContent };
