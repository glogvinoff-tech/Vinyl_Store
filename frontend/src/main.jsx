import React from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import { StoreProvider } from "./app/StoreContext";
import App from "./app/App";
import "./styles/spin.css";

const legacy = window.location.pathname === "/" ? window.location.hash.slice(1) : "";
if (["catalog", "cart", "account", "favorites"].includes(legacy)) {
  window.history.replaceState(null, "", `/${legacy}`);
} else if (legacy.startsWith("album/")) {
  window.history.replaceState(null, "", `/albums/${legacy.slice(6)}`);
}

createRoot(document.getElementById("root")).render(
  <BrowserRouter>
    <StoreProvider>
      <App />
    </StoreProvider>
  </BrowserRouter>,
);
