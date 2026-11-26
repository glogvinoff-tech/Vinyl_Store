import test from "node:test";
import assert from "node:assert/strict";
import { api, setSession } from "../src/api/client.js";

const session = {
  accessToken: "renewed-token",
  user: { id: "test-user", displayName: "Test", roles: ["VIEWER"] },
};
const response = (status, body) => new Response(JSON.stringify(body), { status });

test("expired JWT is refreshed for protected auth actions", async t => {
  for (const path of ["auth/me", "auth/change-password", "auth/change-initial-password"]) {
    await t.test(path, async () => {
      const calls = [];
      setSession({ ...session, accessToken: "expired-token" });
      const originalFetch = globalThis.fetch;
      globalThis.fetch = async (url, options) => {
        calls.push({ url, options });
        if (url === "/api/auth/refresh") return response(200, session);
        if (calls.length === 1) return response(401, { message: "Expired token" });
        return response(200, { success: true });
      };
      try {
        assert.deepEqual(await api(path, path === "auth/me" ? undefined : { newPassword: "test" }), { success: true });
        assert.deepEqual(calls.map(call => call.url), [`/api/${path}`, "/api/auth/refresh", `/api/${path}`]);
        assert.equal(calls[2].options.headers.get("Authorization"), "Bearer renewed-token");
      } finally { globalThis.fetch = originalFetch; setSession(null); }
    });
  }
});

test("wrong credentials and rejected refresh never retry indefinitely", async t => {
  for (const path of ["auth/login", "auth/email/verify", "auth/refresh", "orders"]) {
    await t.test(path, async () => {
      const calls = [];
      const originalFetch = globalThis.fetch;
      globalThis.fetch = async url => {
        calls.push(url);
        return response(401, { message: "Access denied" });
      };
      try {
        await assert.rejects(api(path, {}), error => error.status === 401);
        assert.deepEqual(calls, path === "orders" ? ["/api/orders", "/api/auth/refresh"] : [`/api/${path}`]);
      } finally { globalThis.fetch = originalFetch; setSession(null); }
    });
  }
});
