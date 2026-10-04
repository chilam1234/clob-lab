import { cn } from "@/lib/utils";

function Input({ className, type = "text", ...props }) {
  return (
    <input
      type={type}
      data-slot="input"
      className={cn(
        "h-8 w-full min-w-0 rounded-[2px] border border-border bg-surface px-2 text-sm text-text font-[ui-monospace,monospace] [font-variant-numeric:tabular-nums] outline-none placeholder:text-text-muted focus-visible:border-primary disabled:opacity-50",
        className,
      )}
      {...props}
    />
  );
}

export { Input };
