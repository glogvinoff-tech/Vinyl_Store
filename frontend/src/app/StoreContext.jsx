import { createContext, useCallback, useContext, useEffect, useRef, useState } from "react";
import { api, loadCatalog, restoreSession, setSession } from "../api/client";

const Context = createContext(null);
export const useStore = () => useContext(Context);
const uuid = /^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/i;
function readCart() {
  try {
    return Object.fromEntries(Object.entries(JSON.parse(localStorage.getItem("spin-cart") || "{}") || {})
      .filter(([id, count]) => uuid.test(id) && Number.isInteger(count) && count > 0 && count <= 100));
  } catch { return {}; }
}
function readFavorites() {
  try {
    const value = JSON.parse(localStorage.getItem("spin-favorites") || "[]");
    return Array.isArray(value) ? [...new Set(value.filter(id => typeof id === "string" && uuid.test(id)))] : [];
  } catch { return []; }
}
export function StoreProvider({ children }) {
  const [user, setUser] = useState(null);
  const [records, setRecords] = useState([]);
  const [favorites, setFavorites] = useState(readFavorites);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [cart, setCart] = useState(readCart);
  const [authOpen, setAuthOpen] = useState(false);
  const [notice, setNotice] = useState("");
  const timer = useRef();
  const refreshCatalog = useCallback(async () => setRecords(await loadCatalog()), []);
  async function refresh() {
    const [nextUser] = await Promise.all([restoreSession().then(next => {
      setUser(next);
      setAuthOpen(!next || Boolean(next.passwordChangeRequired));
      return next;
    }), refreshCatalog()]);
    setError("");
    return nextUser;
  }
  async function reload() {
    setLoading(true);
    try { await refresh(); } catch (e) { setError(e.message); } finally { setLoading(false); }
  }
  useEffect(() => { reload(); return () => clearTimeout(timer.current); }, []);
  useEffect(() => { try { localStorage.setItem("spin-cart", JSON.stringify(cart)); } catch {} }, [cart]);
  useEffect(() => { try { localStorage.setItem("spin-favorites", JSON.stringify(favorites)); } catch {} }, [favorites]);
  function notify(message) {
    setNotice(message); clearTimeout(timer.current);
    timer.current = setTimeout(() => setNotice(""), 3500);
  }
  function requireUser() {
    if (user && !user.passwordChangeRequired) return true;
    setAuthOpen(true); return false;
  }
  function changeQuantity(record, value) {
    if (!requireUser() || !Number.isInteger(value)) return;
    setCart(previous => {
      const next = {...previous};
      const quantity = Math.max(0, Math.min(value, record.stock, 100));
      if (quantity) next[record.id] = quantity; else delete next[record.id];
      return next;
    });
  }
  function add(record) {
    if (!requireUser()) return;
    if ((cart[record.id] || 0) >= Math.min(record.stock, 100)) return notify("Достигнут доступный остаток");
    setCart(previous => ({ ...previous, [record.id]: Math.min((previous[record.id] || 0) + 1, record.stock, 100) }));
    notify("Пластинка добавлена в корзину");
  }
  function favorite(id) {
    if (!requireUser()) return;
    setFavorites(previous => previous.includes(id) ? previous.filter(value => value !== id) : [...previous, id]);
  }
  function signIn(session) {
    const next = setSession(session); setUser(next);
    setAuthOpen(Boolean(next?.passwordChangeRequired));
    return next;
  }
  async function logout() {
    await api("auth/logout", {}); setSession(null); setUser(null); setAuthOpen(true);
  }
  return <Context.Provider value={{ user, records, favorites, loading, error, reload, refresh, refreshCatalog, cart, setCart,
    authOpen, setAuthOpen, requireUser, add, changeQuantity, favorite, logout, signIn, notice, notify }}>{children}</Context.Provider>;
}
