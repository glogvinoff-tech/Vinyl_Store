"""Internal HTTP service. Receives catalog metadata, never accounts or real orders."""
import json
import logging
import math
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from uuid import UUID

from model import Recommender
from forecast import forecast_now

MAX_BODY = 2 * 1024 * 1024


def validate_request(payload):
    if not isinstance(payload, dict):
        raise ValueError("Expected an object")
    records, genres, limit = payload.get("records"), payload.get("genres", []), payload.get("limit", 3)
    if not isinstance(records, list) or len(records) > 10000:
        raise ValueError("Invalid catalog size")
    if not isinstance(genres, list) or len(genres) > 8 or any(not isinstance(g, str) or len(g) > 50 for g in genres):
        raise ValueError("Invalid genres")
    if type(limit) is not int or not 1 <= limit <= 12:
        raise ValueError("Invalid limit")
    ids = set()
    for record in records:
        if not isinstance(record, dict) or not isinstance(record.get("id"), str):
            raise ValueError("Invalid record")
        UUID(record["id"])
        if record["id"] in ids:
            raise ValueError("Duplicate record")
        ids.add(record["id"])
        if not isinstance(record.get("genre"), str) or len(record["genre"]) > 50:
            raise ValueError("Invalid genre")
        price = record.get("price")
        if type(price) not in (int, float) or not math.isfinite(price) or not 0 < price <= 99_999_999.99:
            raise ValueError("Invalid price")
        if type(record.get("stock")) is not int or record["stock"] < 0:
            raise ValueError("Invalid stock")
        if type(record.get("discountPercent")) is not int or not 0 <= record["discountPercent"] <= 90:
            raise ValueError("Invalid discount")
    return records, genres, limit


class RecommendationServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address):
        super().__init__(address, Handler)
        self.model = Recommender()


class Handler(BaseHTTPRequestHandler):
    def setup(self):
        super().setup()
        self.connection.settimeout(20)

    def reply(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False, allow_nan=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/forecast":
            try:
                self.reply(200, forecast_now())
            except Exception:
                logging.exception("Forecast failed")
                self.reply(503, {"error": "Forecast unavailable"})
            return
        self.reply(200, {"status": "ok"}) if self.path == "/health" else self.reply(404, {"error": "Not found"})

    def do_POST(self):
        if self.path != "/recommend":
            self.reply(404, {"error": "Not found"})
            return
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if not 0 < size <= MAX_BODY:
                self.reply(413, {"error": "Invalid body size"})
                return
            records, genres, limit = validate_request(json.loads(self.rfile.read(size)))
            self.reply(200, self.server.model.recommend(records, genres, limit))
        except (ValueError, TypeError, KeyError):
            self.reply(400, {"error": "Invalid recommendation request"})
        except (BrokenPipeError, ConnectionResetError, TimeoutError):
            return
        except Exception:
            logging.exception("Recommendation failed")
            self.reply(503, {"error": "Recommendation service unavailable"})


if __name__ == "__main__":
    address = (os.environ.get("RECOMMENDER_HOST", "127.0.0.1"), int(os.environ.get("RECOMMENDER_PORT", "8091")))
    with RecommendationServer(address) as server:
        print(f"SPIN neural recommendations: http://{address[0]}:{address[1]}", flush=True)
        server.serve_forever()
