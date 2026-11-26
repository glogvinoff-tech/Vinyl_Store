import { useStore } from "../app/StoreContext";
export default function BuyControl({record}) {
  const store = useStore(), amount = store.cart[record.id] || 0;
  return amount ? <div className="buy-control"><div className="quantity">
    <button aria-label="Уменьшить количество" onClick={() => store.changeQuantity(record, amount - 1)}>−</button>
    <input aria-label="Количество в корзине" type="number" min="0" max={Math.min(record.stock,100)} value={amount} onChange={e => store.changeQuantity(record, Number(e.target.value))} />
    <button aria-label="Увеличить количество" disabled={amount >= Math.min(record.stock,100)} onClick={() => store.add(record)}>+</button>
  </div><button className="remove-all" onClick={() => store.changeQuantity(record, 0)}>Удалить все</button></div> : <button className="add" disabled={!record.stock} onClick={() => store.add(record)}>{record.stock ? "В корзину" : "Нет в наличии"}</button>;
}
export function Price({record, money}) {
  return <span className="price">{record.discountPercent > 0 && <><del>{money(record.originalPrice)}</del><small className="discount-badge">−{record.discountPercent}%</small></>}{money(record.price)}</span>;
}
