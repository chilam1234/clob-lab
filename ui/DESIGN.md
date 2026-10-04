---
version: alpha
name: Clob Lab Editorial Console
description: >
  Trading console styled after one-ink editorial print (mono-color system,
  Cobalt + Terracotta complementary duotone): warm substrates, restrained
  ink palette, tabular mono numerals, serif display masthead.
colors:
  primary: "#2148B8"
  secondary: "#30343A"
  tertiary: "#C65F38"
  neutral: "#E9E9E5"
  background: "#E9E9E5"
  surface: "#F4F4F1"
  surface-raised: "#FAFAF7"
  text: "#30343A"
  text-muted: "#6B7075"
  border: "#C9CCC6"
  up: "#1A3E92"
  down: "#A84324"
  wick: "#30343A"
  accent-alert: "#C83232"
typography:
  masthead:
    fontFamily: Georgia
    fontSize: 1.75rem
    fontWeight: 700
    lineHeight: 1.1
    letterSpacing: "-0.01em"
  h2:
    fontFamily: Georgia
    fontSize: 0.8125rem
    fontWeight: 700
    lineHeight: 1.2
    letterSpacing: "0.08em"
  body-md:
    fontFamily: ui-monospace
    fontSize: 0.875rem
    fontWeight: 400
    lineHeight: 1.45
  numeral:
    fontFamily: ui-monospace
    fontSize: 1rem
    fontWeight: 600
    lineHeight: 1.2
    fontFeature: "tnum"
rounded:
  sm: 2px
  md: 4px
  lg: 8px
spacing:
  xs: 4px
  sm: 8px
  md: 16px
  lg: 24px
  xl: 32px
components:
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "#FAFAF7"
    rounded: "{rounded.sm}"
    padding: 8px
  button-primary-hover:
    backgroundColor: "#1A3A99"
    textColor: "#FAFAF7"
    rounded: "{rounded.sm}"
    padding: 8px
  button-destructive:
    backgroundColor: "{colors.accent-alert}"
    textColor: "#FAFAF7"
    rounded: "{rounded.sm}"
    padding: 8px
  panel:
    backgroundColor: "{colors.surface-raised}"
    textColor: "{colors.text}"
    rounded: "{rounded.md}"
    padding: 12px
  panel-header:
    backgroundColor: "{colors.surface-raised}"
    textColor: "{colors.text-muted}"
    typography: "{typography.h2}"
  panel-border:
    backgroundColor: "{colors.border}"
    textColor: "{colors.text}"
    padding: 1px
  input:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text}"
    rounded: "{rounded.sm}"
    padding: 6px
  ladder-bid-row:
    backgroundColor: "{colors.surface-raised}"
    textColor: "{colors.up}"
  ladder-ask-row:
    backgroundColor: "{colors.surface-raised}"
    textColor: "{colors.down}"
  trade-up:
    backgroundColor: "{colors.surface-raised}"
    textColor: "{colors.up}"
  trade-down:
    backgroundColor: "{colors.surface-raised}"
    textColor: "{colors.down}"
  candle-up:
    backgroundColor: "{colors.surface-raised}"
    textColor: "{colors.up}"
  candle-down:
    backgroundColor: "{colors.surface-raised}"
    textColor: "{colors.down}"
  candle-wick:
    backgroundColor: "{colors.surface-raised}"
    textColor: "{colors.wick}"
  empty-space:
    backgroundColor: "{colors.neutral}"
    textColor: "{colors.text}"
---

## Overview

A trading console that looks like an editorial print, not a terminal:
warm gray substrate ("paper"), one dominant ink (Cobalt) carrying
structure, and a single accent plate (Terracotta) reserved for
meaningful marks — down-candles, asks, rejections. Per the mono-color
system, the dominant plate carries 70–85% of visible ink and the accent
15–30%; white space is part of the composition. Chrome stays flat:
hairline borders, no shadows or gradients.

## Colors

- **Primary — Cobalt (#2148B8):** the dominant plate. Bid rows,
  up-candles, buttons, chart axes, link text.
- **Tertiary — Terracotta (#C65F38):** the accent plate. Ask rows,
  down-candles, rejected-order feedback. It always means "sell-side or
  error", never decoration.
- **Secondary — Charcoal (#30343A):** wicks, primary text, the
  masthead rule.
- **Neutral — Cool Gray (#E9E9E5):** substrate. Panels sit one step
  brighter (#FAFAF7) to read as paper inlays.
- **Candles:** up = filled Cobalt, down = filled Terracotta, wick =
  Charcoal hairline. No third color at any time; the darker overlap of
  two plates in overprint moments on canvas is composite, not a new
  ink.

## Typography

Georgia serif for the masthead and section labels (editorial voice);
ui-monospace with `tnum` for every numeral (prices, quantities, seq).
No other families.

## Layout

12px panel padding; 8px rhythm inside panels; charts fill their panel
edge to edge with a 4px inner margin. Sections are separated by
hairline borders, not shadows.

## Components

`button-primary` is the only Cobalt-filled element in a panel;
`button-destructive` (Terracotta) is reserved for the cancel/kill
action. Ladder and trade rows keep substrate backgrounds and take only
ink-colored text — tinted row backgrounds would read as a third ink.

## Do's and Don'ts

- Do keep the two-ink discipline: if a third hue appears in a mock, it
  is wrong.
- Don't use pure black (#000); Charcoal is the darkest mark.
- Don't add glow, gradient, or shadow to chart marks; flat ink only.
- Don't tint entire table rows; color the numeral text only.