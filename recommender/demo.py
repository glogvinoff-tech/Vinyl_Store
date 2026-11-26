"""Export fictional purchase history using the public catalog or a standalone demo catalog."""
import argparse
import json
from pathlib import Path
from urllib.request import urlopen
from uuid import UUID

from model import Recommender, generate_history


def load_catalog(base_url):
    records, page = [], 0
    while True:
        with urlopen(f"{base_url.rstrip('/')}/api/catalog?size=100&page={page}", timeout=10) as response:
            result = json.load(response)
        records.extend(result["content"])
        page += 1
        if page >= result["totalPages"]:
            return records


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sample", action="store_true", help="Use a fictional catalog without a running shop")
    parser.add_argument("--shop", default="http://127.0.0.1:8080")
    parser.add_argument("--output", type=Path, default=Path("exports/recommendations-demo.json"))
    args = parser.parse_args()
    records = ([{"id": str(UUID(int=i + 1)), "genre": ["Rock", "Jazz", "Pop", "Electronic"][i % 4],
                 "price": 1500 + i * 200, "stock": 10, "discountPercent": 20 if i % 3 == 0 else 0}
                for i in range(24)] if args.sample else load_catalog(args.shop))
    records.sort(key=lambda record: record["id"])
    if not records:
        parser.error("The catalog is empty")
    _, _, history = generate_history(records)
    result = Recommender().recommend(records, ["Rock"])
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({"dataSource": "SYNTHETIC", "catalog": records, "history": history, "example": result},
                                    ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Exported {len(history)} fictional events to {args.output}")
    print(json.dumps(result["training"], ensure_ascii=True, indent=2))
