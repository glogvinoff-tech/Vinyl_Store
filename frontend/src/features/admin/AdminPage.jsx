import { useEffect, useState } from "react";
import { useSearchParams, Link } from "react-router-dom";
import { api, money, recordModel } from "../../api/client";
import { useStore } from "../../app/StoreContext";
import RecordEditor from "./RecordEditor";
import SalesForecast from "./SalesForecast";
export default function AdminPage() {
  const store = useStore();
  const [params, setParams] = useSearchParams();
  const [forecastOpen, setForecastOpen] = useState(() => params.get("forecast") === "1");
  const [records, setRecords] = useState([]), [editing, setEditing] = useState(null), [busy, setBusy] = useState(null), [error, setError] = useState("");
  async function load() {
    try { const loaded = (await api("records?activeOnly=false")).map(recordModel); setRecords(loaded); if (params.get("edit")) setEditing(loaded.find(r => r.id === params.get("edit")) || null); else if (params.has("new")) setEditing({}); if (params.has("edit") || params.has("new")) setParams({}, {replace:true}); setError(""); } catch (e) { setError(e.message); }
  }
  useEffect(() => { if (store.user?.role === "admin" && !store.user.passwordChangeRequired) load(); }, [store.user?.id, store.user?.passwordChangeRequired]);
  if (store.user?.role !== "admin" || store.user.passwordChangeRequired) return <section className="page"><h2>Доступ только для администратора</h2><button className="button" onClick={() => store.setAuthOpen(true)}>Войти</button></section>;
  async function toggle(record) {
    setBusy(record.id);
    try { await api(`records/${record.id}/active?active=${!record.active}&version=${record.version}`, undefined, { method: "PATCH" }); await load(); await store.refresh(); }
    catch (e) { setError(e.message); } finally { setBusy(null); }
  }
  return <section className="page"><p className="eyebrow">АДМИНИСТРАТОР</p><h2>Управление каталогом</h2>
    <div className="actions"><button className="button dark" onClick={() => setEditing({})}>Добавить пластинку +</button>
      <Link className="button" to="/orders">Мои заказы</Link><a className="button" href="/admin.html">Все заказы, CSV, аналитика, пользователи и аудит ↗</a></div>
    <div className="actions"><button className="button" onClick={() => setForecastOpen(true)}>ИИ-прогноз продаж ↗</button><span>Синтетическая аналитика и план выручки</span></div>
    {forecastOpen && <SalesForecast onClose={() => { setForecastOpen(false); if (params.has("forecast")) { const next = new URLSearchParams(params); next.delete("forecast"); setParams(next, { replace: true }); } }} />}
    <p>{records.length} пластинок · Остаток: {records.reduce((n, r) => n + r.stock, 0)} шт.</p>
    {error && <div role="alert" className="form-error">{error} <button onClick={load}>Обновить</button></div>}
    {records.map(record => <div className="admin-row" key={record.id}>
      <span><b>{record.artist}</b><br />{record.title}<br />{money(record.price)} {record.discountPercent > 0 && ` (скидка ${record.discountPercent}%)`} · {record.stock} шт. · {record.active ? "Активна" : "Отключена"}</span>
      <button disabled={busy === record.id} onClick={() => setEditing(record)}>Изменить</button>
      <button disabled={busy === record.id} onClick={() => toggle(record)}>{record.active ? "Отключить" : "Включить"}</button>
    </div>)}
    {editing && <RecordEditor key={editing.id || "new"} record={editing} covers={records} onClose={() => setEditing(null)} onSaved={async () => { await load(); await store.refresh(); setEditing(null); }} />}
  </section>;
}
