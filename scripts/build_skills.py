#!/usr/bin/env python3
"""Validate data/skills.json for the Claude skills top-10 dataset."""

from __future__ import annotations

import json
import sys
from pathlib import Path
from urllib.parse import urlparse

REQUIRED_FIELDS = ("rank", "name", "repo_url", "stars", "description", "pros", "cons", "category")
EXPECTED_COUNT = 10
DATA_PATH = Path(__file__).resolve().parent.parent / "data" / "skills.json"


def is_valid_github_url(url: str) -> bool:
    parsed = urlparse(url)
    if parsed.scheme not in ("http", "https"):
        return False
    if parsed.netloc not in ("github.com", "www.github.com"):
        return False
    parts = [p for p in parsed.path.split("/") if p]
    return len(parts) >= 2


def validate_skills(data: object) -> list[str]:
    errors: list[str] = []

    if not isinstance(data, list):
        return ["root must be a JSON array"]

    if len(data) != EXPECTED_COUNT:
        errors.append(f"expected exactly {EXPECTED_COUNT} records, got {len(data)}")

    ranks: list[int] = []

    for index, item in enumerate(data):
        prefix = f"record[{index}]"

        if not isinstance(item, dict):
            errors.append(f"{prefix} must be an object")
            continue

        for field in REQUIRED_FIELDS:
            if field not in item:
                errors.append(f"{prefix} missing field '{field}'")
            elif item[field] in (None, "", []):
                errors.append(f"{prefix}.{field} must be non-empty")

        rank = item.get("rank")
        if not isinstance(rank, int):
            errors.append(f"{prefix}.rank must be an integer")
        else:
            ranks.append(rank)

        name = item.get("name")
        if not isinstance(name, str) or not name.strip():
            errors.append(f"{prefix}.name must be a non-empty string")

        repo_url = item.get("repo_url")
        if not isinstance(repo_url, str) or not is_valid_github_url(repo_url):
            errors.append(f"{prefix}.repo_url must be a valid GitHub URL")

        stars = item.get("stars")
        if not isinstance(stars, int) or stars < 0:
            errors.append(f"{prefix}.stars must be a non-negative integer")

        description = item.get("description")
        if not isinstance(description, str) or not description.strip():
            errors.append(f"{prefix}.description must be a non-empty string")

        pros = item.get("pros")
        if not isinstance(pros, list) or len(pros) < 1:
            errors.append(f"{prefix}.pros must be a non-empty array")
        elif not all(isinstance(p, str) and p.strip() for p in pros):
            errors.append(f"{prefix}.pros entries must be non-empty strings")

        cons = item.get("cons")
        if not isinstance(cons, list) or len(cons) < 1:
            errors.append(f"{prefix}.cons must be a non-empty array")
        elif not all(isinstance(c, str) and c.strip() for c in cons):
            errors.append(f"{prefix}.cons entries must be non-empty strings")

        category = item.get("category")
        if not isinstance(category, str) or not category.strip():
            errors.append(f"{prefix}.category must be a non-empty string")

    expected_ranks = set(range(1, EXPECTED_COUNT + 1))
    if set(ranks) != expected_ranks:
        errors.append(f"rank must be unique and cover 1..{EXPECTED_COUNT}, got {sorted(ranks)}")

    if isinstance(data, list) and len(data) == EXPECTED_COUNT:
        sorted_by_rank = sorted(
            (x for x in data if isinstance(x, dict)),
            key=lambda x: x.get("rank", 0),
        )
        if sorted_by_rank != data:
            errors.append("array must be sorted by rank ascending")

    return errors


def main() -> int:
    path = Path(sys.argv[1]) if len(sys.argv) > 1 else DATA_PATH

    if not path.exists():
        print(f"ERROR: file not found: {path}", file=sys.stderr)
        return 1

    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        print(f"ERROR: invalid JSON: {exc}", file=sys.stderr)
        return 1

    errors = validate_skills(data)
    if errors:
        print(f"FAIL: {path}", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 1

    print(f"OK: {path} — {len(data)} records validated")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
