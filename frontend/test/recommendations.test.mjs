import test from "node:test";
import assert from "node:assert/strict";
import { preferenceKey, readPreferences, savePreferences, recommendationQuery } from "../src/features/recommendations/preferences.js";

test("taste profiles are isolated between guests and different accounts", () => {
  const values = new Map();
  const storage = { getItem: key => values.get(key), setItem: (key, value) => values.set(key, value) };
  savePreferences(storage, "alice", ["Rock"]);
  savePreferences(storage, "bob", ["Jazz"]);
  assert.deepEqual(readPreferences(storage, "alice", ["Rock", "Jazz"]), ["Rock"]);
  assert.deepEqual(readPreferences(storage, "bob", ["Rock", "Jazz"]), ["Jazz"]);
  assert.deepEqual(readPreferences(storage, null, ["Rock", "Jazz"]), []);
  assert.notEqual(preferenceKey("alice"), preferenceKey(null));
});

test("corrupted or unavailable storage does not break the storefront", () => {
  for (const value of ["null", "{}", "broken", "123"]) {
    assert.deepEqual(readPreferences({ getItem: () => value }, null, ["Rock"]), []);
  }
  assert.deepEqual(readPreferences(null, null, []), []);
  assert.doesNotThrow(() => savePreferences(null, null, ["Rock"]));
  assert.deepEqual(readPreferences({ getItem: () => '["Rock", "Rock", 42, "deleted genre"]' }, null, ["Rock"]), ["Rock"]);
});

test("genre query is encoded and bounded without including a user's identity", () => {
  const query = recommendationQuery(["Hip Hop", "R&B", "Поп"]);
  const params = new URL(`https://example.test/${query}`).searchParams;
  assert.deepEqual(params.getAll("genres"), ["Hip Hop", "R&B", "Поп"]);
  assert.equal(params.get("limit"), "3");
  assert.equal(new URL(`https://example.test/${recommendationQuery(Array(12).fill("Rock"))}`).searchParams.getAll("genres").length, 8);
});
