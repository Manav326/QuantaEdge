#!/usr/bin/env python3
"""Prepare the Google Play 512×512 product icon from QuantaEdge's canonical Q mark."""
from __future__ import annotations

import argparse
from pathlib import Path

from PIL import Image


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parents[3]
    source_path = repo_root / "apps" / "web" / "public" / "branding" / "quantaedge-icon.png"
    if not source_path.is_file():
        raise SystemExit(f"Canonical QuantaEdge Q icon not found: {source_path}")

    with Image.open(source_path) as source:
        source.load()
        if source.width != source.height:
            raise SystemExit(
                f"Expected a square canonical Q icon; got {source.width}x{source.height}."
            )
        # Play requires a full-square opaque 512px PNG and applies its own rounded mask.
        # Composite transparency over white, keeping the original Q artwork unchanged.
        q_mark = source.convert("RGBA").resize((512, 512), Image.Resampling.LANCZOS)
        canvas = Image.new("RGBA", (512, 512), (255, 255, 255, 255))
        canvas.alpha_composite(q_mark)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        canvas.convert("RGB").save(args.output, format="PNG", optimize=True)

    with Image.open(args.output) as result:
        if result.size != (512, 512) or result.mode != "RGB":
            raise SystemExit("Generated store icon failed the 512x512 opaque PNG check.")
    print(f"Created {args.output} (512x512 RGB PNG).")


if __name__ == "__main__":
    main()
