package ru.mirea.vinylstore.domain;

/** Рассчитанный уровень приоритета. */
public enum PriorityLevel {
    /** Приоритет не рассчитывается для завершённых и отменённых заказов. */
    NOT_APPLICABLE,
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}
