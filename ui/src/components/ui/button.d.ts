import type { ButtonHTMLAttributes, ReactNode } from "react";

export declare const buttonVariants: (...args: unknown[]) => string;

export declare function Button(
  props: ButtonHTMLAttributes<HTMLButtonElement> & {
    className?: string;
    variant?: "default" | "destructive" | "outline" | "ghost" | "link";
    size?: "default" | "sm" | "lg" | "icon";
  },
): ReactNode;
