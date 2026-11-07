import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { cn } from "@/lib/utils";
import type {
  A2UIComponent,
  A2UISchema,
  ActionGroupProps,
  BannerProps,
  BannerStatus,
  DataTableProps,
  MetricCardProps,
  RendererProps,
} from "./types";

function spanClass(span: A2UIComponent["grid_span"]): string {
  // 12-col responsive spans mapped to Tailwind grid-column utilities.
  const map: Record<number, string> = {
    4: "col-span-12 md:col-span-6 lg:col-span-4",
    6: "col-span-12 md:col-span-6",
    12: "col-span-12",
  };
  return map[span.lg] ?? map[span.md] ?? "col-span-12";
}

function bannerTone(status: BannerStatus): string {
  if (status === "danger") {
    return "border-accent-alert bg-[#f8ece9] text-accent-alert";
  }
  if (status === "warning") {
    return "border-tertiary bg-[#f7f0ea] text-tertiary";
  }
  return "border-up bg-[#e8eef8] text-up";
}

function Banner({ status, title, description }: BannerProps) {
  return (
    <div
      role="alert"
      className={cn(
        "rounded-[4px] border px-4 py-3",
        bannerTone(status),
      )}
    >
      <div className="font-h2 text-h2 font-bold uppercase tracking-h2">{title}</div>
      <p className="mt-1 text-sm text-text">{description}</p>
    </div>
  );
}

function MetricCard({ label, value, helper_text, change_value }: MetricCardProps) {
  return (
    <Card className="h-full">
      <CardHeader>
        <CardTitle>{label}</CardTitle>
      </CardHeader>
      <CardContent>
        <div className="text-numeral font-numeral text-2xl text-text">{value}</div>
        <div className="mt-1 flex items-center gap-2 text-xs text-text-muted">
          {helper_text ? <span>{helper_text}</span> : null}
          {change_value ? (
            <span className="text-tertiary">Δ {change_value}</span>
          ) : null}
        </div>
      </CardContent>
    </Card>
  );
}

function rowTone(tone?: string): string {
  if (tone === "danger") return "bg-[#f8ece9]";
  if (tone === "warning") return "bg-[#f7f0ea]";
  return "";
}

function DataTable({ title, headers, rows }: DataTableProps) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>{title}</CardTitle>
      </CardHeader>
      <CardContent>
        <Table>
          <TableHeader>
            <TableRow>
              {headers.map((h) => (
                <TableHead key={h}>{h}</TableHead>
              ))}
            </TableRow>
          </TableHeader>
          <TableBody>
            {rows.map((row, i) => (
              <TableRow key={i} className={rowTone(row.meta?.tone)}>
                {row.cells.map((cell, j) => (
                  <TableCell key={j}>{cell}</TableCell>
                ))}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </CardContent>
    </Card>
  );
}

function ActionGroup({
  alignment = "right",
  actions,
  onActionTriggered,
}: ActionGroupProps & {
  onActionTriggered?: (actionId: string, payload: unknown) => void;
}) {
  const justify =
    alignment === "left"
      ? "justify-start"
      : alignment === "center"
        ? "justify-center"
        : "justify-end";

  return (
    <div className={cn("flex flex-wrap gap-2", justify)}>
      {actions.map((action) => {
        const variant =
          action.variant === "destructive"
            ? "destructive"
            : action.variant === "secondary"
              ? "outline"
              : "default";
        return (
          <Button
            key={action.action_id}
            variant={variant}
            onClick={() => onActionTriggered?.(action.action_id, action.payload)}
          >
            {action.label}
          </Button>
        );
      })}
    </div>
  );
}

function renderComponent(
  component: A2UIComponent,
  onActionTriggered?: (actionId: string, payload: unknown) => void,
) {
  switch (component.type) {
    case "Banner":
      return <Banner {...(component.props as unknown as BannerProps)} />;
    case "MetricCard":
      return <MetricCard {...(component.props as unknown as MetricCardProps)} />;
    case "DataTable":
      return <DataTable {...(component.props as unknown as DataTableProps)} />;
    case "ActionGroup":
      return (
        <ActionGroup
          {...(component.props as unknown as ActionGroupProps)}
          onActionTriggered={onActionTriggered}
        />
      );
    default:
      return (
        <div className="text-sm text-accent-alert">
          Unknown A2UI component type: {String((component as A2UIComponent).type)}
        </div>
      );
  }
}

export function A2UIRenderer({ schema, onActionTriggered }: RendererProps) {
  const gap = schema.layout.gap || "16px";
  return (
    <div
      data-a2ui-version={schema.a2ui_version}
      className="grid grid-cols-12"
      style={{ gap }}
    >
      {schema.components.map((component) => (
        <div key={component.id} className={spanClass(component.grid_span)}>
          {renderComponent(component, onActionTriggered)}
        </div>
      ))}
    </div>
  );
}

export type { A2UISchema, RendererProps };
