export interface GridSpan {
  sm: number;
  md: number;
  lg: number;
}

export type A2UIComponentType = "Banner" | "MetricCard" | "DataTable" | "ActionGroup";

export interface A2UIComponent {
  id: string;
  type: A2UIComponentType;
  grid_span: GridSpan;
  props: Record<string, unknown>;
}

export interface A2UISchema {
  a2ui_version: string;
  layout: { type: string; columns: number; gap: string };
  components: A2UIComponent[];
}

export interface RendererProps {
  schema: A2UISchema;
  onActionTriggered?: (actionId: string, payload: unknown) => void;
}

export type BannerStatus = "warning" | "danger" | "success";

export interface BannerProps {
  status: BannerStatus;
  title: string;
  description: string;
}

export interface MetricCardProps {
  label: string;
  value: string;
  helper_text?: string;
  change_value?: string;
}

export interface DataTableRow {
  cells: string[];
  meta?: { tone?: string };
}

export interface DataTableProps {
  title: string;
  headers: string[];
  rows: DataTableRow[];
}

export interface ActionDef {
  label: string;
  action_id: string;
  variant?: "primary" | "secondary" | "destructive";
  payload?: unknown;
}

export interface ActionGroupProps {
  alignment?: "left" | "right" | "center";
  actions: ActionDef[];
}
