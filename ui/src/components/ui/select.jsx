import { cn } from "@/lib/utils";

function Select({ className, ...props }) {
  return (
    <select
      data-slot="select"
      className={cn(
        "h-8 w-full rounded-[2px] border border-border bg-surface px-2 text-sm text-text font-[ui-monospace,monospace] outline-none focus-visible:border-primary disabled:opacity-50",
        className,
      )}
      {...props}
    />
  );
}

export { Select };
