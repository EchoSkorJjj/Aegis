# Diagrams

The root README's two diagrams. Each `.svg` is the source; the `.png` next to it is what the README shows, rendered
from it.

| Diagram | Source | Image |
|---|---|---|
| Services and how they talk | [`system-architecture.svg`](system-architecture.svg) | [`system-architecture.png`](system-architecture.png) |
| Aegis on AWS | [`aws-architecture.svg`](aws-architecture.svg) | [`aws-architecture.png`](aws-architecture.png) |

## Changing one

Edit the `.svg`: it's plain SVG, with boxes, text and arrows at fixed coordinates. Open it in a browser to check it,
then render the PNGs again:

```bash
node assets/diagrams/render.mjs
```

[`render.mjs`](render.mjs) renders every `.svg` here to the `.png` beside it, at twice its size so the text stays
sharp, using the Playwright that the frontend's end-to-end tests already install. Iconify sometimes refuses a burst of
requests, so the script retries each diagram and writes a PNG only once every icon has loaded. If one still fails, it
names the icon, leaves that PNG as it was and exits with an error.

## Icons

The icons load from [Iconify](https://iconify.design) when the SVG opens, so rendering needs the internet:

- AWS architecture icons and brand logos come from its `logos` set.
- Glyphs (users, globe, locks, mail) come from `lucide`.
- Groq's logo comes from `thesvg-color`.

Amazon ECR and AWS Cloud Map have no icon there, so they're drawn as AWS-style category tiles: orange for containers,
purple for networking. GitHub shows only the PNG, because it doesn't load images from inside an SVG.
