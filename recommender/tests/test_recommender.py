import copy
import json
from pathlib import Path
import sys
import threading
import unittest
from datetime import datetime, timezone
from urllib.request import Request, urlopen
from urllib.error import HTTPError
from uuid import UUID

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from neural import NeuralNetwork
from model import Recommender, features, generate_history, promotion_title, MOSCOW, TRAINING_SIZE
from server import RecommendationServer, validate_request


def catalog():
    return [{"id": str(UUID(int=i + 1)), "genre": ["Rock", "Jazz", "Pop", "Electronic"][i % 4],
             "price": 1500 + i * 200, "stock": 10, "discountPercent": 20 if i % 3 == 0 else 0} for i in range(24)]


class NeuralTests(unittest.TestCase):
    def test_learns_and_beats_baseline_on_unseen_examples(self):
        x, quantities, _ = generate_history(catalog())
        network = NeuralNetwork()
        initial = network.loss(x[TRAINING_SIZE:], quantities[TRAINING_SIZE:])
        network.train(x[:TRAINING_SIZE], quantities[:TRAINING_SIZE])
        loss = network.loss(x[TRAINING_SIZE:], quantities[TRAINING_SIZE:])
        training, validation = quantities[:TRAINING_SIZE], quantities[TRAINING_SIZE:]
        constant = np.average(training > 0, weights=np.maximum(1, training))
        baseline = np.average(-np.where(validation > 0, np.log(constant), np.log1p(-constant)), weights=np.maximum(1, validation))
        self.assertLess(loss, initial)
        self.assertLess(loss, baseline - 0.05)
        now = datetime(2026, 11, 23, 19, tzinfo=MOSCOW)
        match, other = network.predict(np.array([features(catalog()[0], {genre}, now) for genre in ["rock", "jazz"]]))
        self.assertGreater(match, other + 0.25)
        print(f"\nHeld-out loss: initial={initial:.4f}; constant={baseline:.4f}; neural={loss:.4f}")

    def test_reproducible_history_has_quantities_and_negative_examples(self):
        first = generate_history(catalog())
        second = generate_history(catalog())
        np.testing.assert_array_equal(first[0], second[0])
        self.assertEqual(first[2], second[2])
        self.assertEqual(len(first[2]), 4500)
        self.assertTrue(np.any(first[1] == 0))
        self.assertTrue(np.any(first[1] > 1))

    def test_cold_start_tastes_stock_cache_and_no_catalog_mutation(self):
        records = catalog()
        original = copy.deepcopy(records)
        model = Recommender()
        now = datetime(2026, 11, 23, 19, tzinfo=MOSCOW)
        rock = model.recommend(records, ["Rock"], now=now)
        jazz = model.recommend(records, ["Jazz"], now=now)
        by_id = {record["id"]: record for record in records}
        self.assertEqual(by_id[rock["items"][0]["recordId"]]["genre"], "Rock")
        self.assertEqual(by_id[jazz["items"][0]["recordId"]]["genre"], "Jazz")
        self.assertEqual(records, original)
        self.assertEqual(len(model.recommend(records, now=now)["items"]), 3)
        self.assertTrue(all(by_id[p["recordId"]]["discountPercent"] > 0 for p in rock["promotion"]["items"]))
        cached = model._cached
        records[0]["stock"] = 0
        result = model.recommend(records, ["Rock"], limit=12, now=now)
        self.assertIs(model._cached, cached)
        self.assertNotIn(records[0]["id"], [p["recordId"] for p in result["items"]])
        records[0]["discountPercent"] = 10
        model.recommend(records, now=now)
        self.assertIsNot(model._cached, cached)
        self.assertEqual(model.recommend([])["items"], [])
        for record in records:
            record["stock"] = 0
        self.assertEqual(model.recommend(records)["items"], [])

    def test_date_hour_month_and_moscow_timezone(self):
        record = catalog()[0]
        dates = [datetime(2028, 2, 29, 1, tzinfo=MOSCOW), datetime(2028, 2, 29, 19, tzinfo=MOSCOW),
                 datetime(2028, 3, 1, 1, tzinfo=MOSCOW)]
        encoded = [features(record, set(), date) for date in dates]
        self.assertTrue(np.isfinite(encoded).all())
        self.assertNotEqual(encoded[0], encoded[1])
        self.assertNotEqual(encoded[0], encoded[2])
        model = Recommender()
        first = model.recommend(catalog(), now=dates[0])
        second = model.recommend(catalog(), now=dates[1])
        self.assertNotEqual(first["items"], second["items"])
        result = model.recommend(catalog(), now=datetime(2026, 11, 25, 21, 30, tzinfo=timezone.utc))
        self.assertTrue(result["generatedAt"].startswith("2026-11-26T00:30"))
        self.assertEqual(result["promotion"]["title"], "Находки конца месяца")
        self.assertEqual(promotion_title(datetime(2026, 12, 25)), "Пластинки к Новому году")


class HttpTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = RecommendationServer(("127.0.0.1", 0))
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.url = f"http://127.0.0.1:{cls.server.server_port}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join()

    def test_health_and_real_http_prediction(self):
        with urlopen(self.url + "/health") as response:
            self.assertEqual(json.load(response)["status"], "ok")
        request = Request(self.url + "/recommend", json.dumps({"records": catalog(), "genres": ["Rock"], "limit": 2}).encode(),
                          {"Content-Type": "application/json"})
        with urlopen(request) as response:
            payload = json.load(response)
        self.assertEqual(len(payload["items"]), 2)
        self.assertEqual(payload["training"]["trainingExamples"], 3600)

    def test_bad_payload_is_rejected(self):
        for payload in [{}, {"records": [], "limit": 13}, {"records": catalog(), "genres": ["x"] * 9}]:
            with self.subTest(payload=payload), self.assertRaises(HTTPError) as error:
                urlopen(Request(self.url + "/recommend", json.dumps(payload).encode()))
            self.assertEqual(error.exception.code, 400)
            error.exception.close()
        for field, value in [("price", float("nan")), ("stock", -1), ("discountPercent", 100), ("id", "bad-id")]:
            records = catalog()
            records[0][field] = value
            with self.assertRaises(ValueError):
                validate_request({"records": records})


if __name__ == "__main__":
    unittest.main()
