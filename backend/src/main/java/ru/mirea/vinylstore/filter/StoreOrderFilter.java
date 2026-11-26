package ru.mirea.vinylstore.filter;

import ru.mirea.vinylstore.domain.PriorityLevel;
import ru.mirea.vinylstore.domain.OrderStatus;

import java.math.BigDecimal;
import java.util.List;

/** Результат безопасного разбора строки умного фильтра. */
public record StoreOrderFilter(
        String freeText,
        String record,
        OrderStatus status,
        Boolean paid,
        PriorityLevel priority,
        String tag,
        Boolean archived,
        List<PriceConstraint> prices) {

    public enum Operator { LT, LTE, EQ, GTE, GT }
    public record PriceConstraint(Operator operator, BigDecimal value) { }
}
