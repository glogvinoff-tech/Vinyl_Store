import { useRef, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useStore } from "../../app/StoreContext";
import { api, money } from "../../api/client";
import AlbumCard, { Cover } from "../../components/AlbumCard";
import { DesignIcon, cartAssets } from "../../components/Design";
export default function CartPage() {
  const store = useStore();
  const navigate = useNavigate();
  const [checkout, setCheckout] = useState(false), [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  const [selected, setSelected] = useState([]);
  const submitting = useRef(false);
  const lines = Object.entries(store.cart).map(([id, quantity]) => ({ id, quantity, record: store.records.find(r => r.id === id) }));
  const total = lines.reduce((sum, line) => sum + (line.record?.price || 0) * line.quantity, 0);
  const savings = lines.reduce((sum, line) => sum + ((line.record?.originalPrice || 0) - (line.record?.price || 0)) * line.quantity, 0);
  const count = lines.reduce((sum, line) => sum + line.quantity, 0);
  const unavailable = lines.some(line => !line.record || line.quantity > Math.min(line.record.stock, 100));

  function quantity(id, next) {
    store.setCart(previous => { const copy = { ...previous }; if (next <= 0) delete copy[id]; else copy[id] = next; return copy; });
  }
  async function submit(event) {
    event.preventDefault();
    if (submitting.current || unavailable || !store.requireUser()) return;
    submitting.current = true;
    setBusy(true);
    setError("");
    const form = Object.fromEntries(new FormData(event.currentTarget));
    const request = {
      ...form,
      lines: lines.map(({ id, quantity }) => ({ recordId: id, quantity })),
    };
    try {
      await api("shop/checkout", request);
      store.setCart(previous => {
        const next = { ...previous };
        for (const { recordId, quantity } of request.lines) {
          const remaining = (next[recordId] || 0) - quantity;
          if (remaining > 0) next[recordId] = remaining;
          else delete next[recordId];
        }
        return next;
      });
      store.refreshCatalog().catch(() => {});
      store.notify("Заказ на самовывоз оформлен");
      navigate("/orders");
    } catch (e) {
      setError(e.message);
    } finally {
      submitting.current = false;
      setBusy(false);
    }
  }
  return <section className="cart-page">
    <h1>Корзина ({count})</h1>
    {!lines.length ? <div className="empty">Корзина пуста. <Link to="/catalog">Выбрать пластинку →</Link></div> :
      <div className="cart-layout"><div className="cart-items">
        <div className="cart-bulk"><label><input type="checkbox" checked={lines.every(l => selected.includes(l.id))} disabled={busy} onChange={e => setSelected(e.target.checked ? lines.map(l => l.id) : [])} /> Выбрать все</label><button disabled={busy || !selected.some(id => store.cart[id])} onClick={() => {store.setCart(previous => Object.fromEntries(Object.entries(previous).filter(([id]) => !selected.includes(id)))); setSelected([]);}}>Удалить выбранные</button><button disabled={busy} onClick={() => store.setCart({})}>Очистить корзину</button></div>
        {lines.map(({ id, record, quantity: amount }) => <div className="cart-line" key={id}>
          <input type="checkbox" aria-label={`Выбрать ${record?.title || "товар"}`} checked={selected.includes(id)} disabled={busy} onChange={e => setSelected(previous => e.target.checked ? [...previous, id] : previous.filter(value => value !== id))} />
          {record && <Cover record={record} />}
          <div className="line-info"><strong>{record ? <Link to={`/albums/${id}`}>{record.title}</Link> : "Пластинка недоступна"}</strong>
            <small>{record?.artist}</small><p>{record ? "Виниловая пластинка" : "Удалите товар из корзины"}</p>
            {record && amount > record.stock && <p role="alert">Недостаточно на складе</p>}</div>
          <span className="cart-line-price">{record && money(record.price * amount)}</span>
          <div className="cart-line-actions"><div className="quantity">
            <button disabled={busy} onClick={() => quantity(id, amount - 1)} aria-label="Уменьшить количество">−</button><input aria-label="Количество" type="number" min="1" max={Math.min(record?.stock || 0,100)} value={amount} disabled={busy || !record} onChange={e => {const n = Number(e.target.value); if (Number.isInteger(n) && n > 0 && n <= Math.min(record.stock,100)) quantity(id,n);}} />
            <button disabled={busy || !record || amount >= Math.min(record.stock, 100)} onClick={() => quantity(id, amount + 1)} aria-label="Увеличить количество">+</button>
          </div><button className="remove" disabled={busy} onClick={() => quantity(id, 0)} aria-label="Удалить из корзины"><DesignIcon src={cartAssets.imgTrash} /></button></div>
        </div>)}
      </div><aside className="cart-summary"><h2>Итог</h2>
        <div className="summary-line"><span>Товары ({count})</span><b>{money(total)}</b></div>
        {savings > 0 && <div className="summary-line"><span>Ваша экономия</span><b>{money(savings)}</b></div>}
        <div className="summary-line"><span>Самовывоз</span><b>Бесплатно</b></div>
        <div className="total"><span>Итого</span><span>{money(total)}</span></div>
        {checkout && store.user ? <form className="checkout-form" onSubmit={submit}>
          <label>Получатель<input name="customerName" autoComplete="name" required maxLength={100} defaultValue={store.user.name} disabled={busy} /></label>
          <label>Телефон<input name="phone" type="tel" autoComplete="tel" pattern="\+7[0-9]{10}" placeholder="+79991234567" required maxLength={12} disabled={busy} /></label>
          <button className="button checkout-button" disabled={busy || unavailable}>{busy ? "Оформляем…" : "Подтвердить заказ"}</button>
        </form> : <button className="button checkout-button" disabled={busy || unavailable} onClick={() => { if (store.requireUser()) setCheckout(true); }}>Оформить заказ</button>}
        {error && <div className="form-error" role="alert">{error}</div>}
        <p><Link to="/orders">Мои заказы</Link></p>
      </aside></div>}
    <section className="collection-panel related"><h2>Вам может понравиться</h2><div className="record-grid">
      {store.records.filter(r => !store.cart[r.id]).slice(0, 4).map(record => <AlbumCard key={record.id} record={record} />)}
    </div></section>
  </section>;
}
