import { useEffect, useState } from "react";
import { useStore } from "../../app/StoreContext";
import { api, money } from "../../api/client";
export const statuses = { NEW: "Новый", PAID: "Оплачен", SHIPPED: "Отправлен", COMPLETED: "Завершён", CANCELED: "Отменён" };
export default function OrdersPage() {
  const store = useStore();
  const [orders, setOrders] = useState(null), [error, setError] = useState("");
  async function load() {
    setError("");
    try { setOrders(await api("shop/orders")); } catch (e) { setError(e.message); }
  }
  useEffect(() => { setOrders(null); if (store.user && !store.user.passwordChangeRequired) load(); }, [store.user?.id, store.user?.passwordChangeRequired]);
  return <section className="page narrow"><h2>Мои заказы</h2>
    {!store.user || store.user.passwordChangeRequired ? <button className="button" onClick={() => store.setAuthOpen(true)}>Войти</button> : <>
      {store.user.role !== "customer" && <p><a className="button" href="/admin.html">Все заказы магазина и служебные операции ↗</a></p>}
      {error && <div role="alert">{error} <button onClick={load}>Повторить</button></div>}
      {!orders && !error && <p>Загрузка заказов…</p>}
      {orders?.length === 0 && <div className="empty">Заказов пока нет.</div>}
      {orders?.map(order => <article className="order" key={order.id}>
        <div className="order-heading"><strong>{order.albumTitle} · {money(order.totalPrice)}</strong><span className="status">{statuses[order.status]}</span></div>
        <p>{order.artist} × {order.quantity}</p><p>{new Date(order.createdAt).toLocaleString("ru-RU")}</p>
        <p>{order.customerName} · {order.phone}</p><small>{order.externalId}</small>
      </article>)}
    </>}
  </section>;
}
