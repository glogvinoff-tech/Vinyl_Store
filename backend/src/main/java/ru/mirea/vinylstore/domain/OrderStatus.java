package ru.mirea.vinylstore.domain;

/** Состояние заказа. */
public enum OrderStatus {
    NEW,
    PAID,
    CANCELED,
    COMPLETED,
    SHIPPED
}
