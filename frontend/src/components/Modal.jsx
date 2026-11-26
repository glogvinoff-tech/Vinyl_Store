import { useEffect, useRef } from "react";
export default function Modal({ title, onClose, busy = false, dismissible = true, children }) {
  const ref = useRef(null);
  useEffect(() => {
    const previous = document.activeElement;
    const dialog = ref.current;
    dialog.showModal();
    return () => { if (dialog.open) dialog.close(); if (previous?.isConnected) previous.focus(); };
  }, []);
  useEffect(() => {
    const dialog = ref.current;
    const handleCancel = event => {
      event.preventDefault();
      if (dismissible && !busy) onClose();
    };
    dialog.addEventListener("cancel", handleCancel);
    return () => dialog.removeEventListener("cancel", handleCancel);
  }, [busy, dismissible, onClose]);
  return <dialog ref={ref} aria-label={title}
    onClick={event => {
      if (!dismissible || busy || event.target !== ref.current) return;
      const rect = ref.current.getBoundingClientRect();
      if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) onClose();
    }}>
    {dismissible && <button className="close" aria-label="Закрыть" onClick={onClose} disabled={busy}>×</button>}
    {children}
  </dialog>;
}
