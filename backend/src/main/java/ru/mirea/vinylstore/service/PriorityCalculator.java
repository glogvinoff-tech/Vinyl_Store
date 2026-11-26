package ru.mirea.vinylstore.service;

import org.springframework.stereotype.Component;
import ru.mirea.vinylstore.domain.PriorityLevel;
import ru.mirea.vinylstore.domain.OrderStatus;
import java.math.BigDecimal;

/** Приоритет обработки: завершённые заказы исключаются, оплаченные крупные обрабатываются первыми. */
@Component
public class PriorityCalculator {
    public PriorityLevel calculate(OrderStatus status, BigDecimal total, boolean paid) {
        if (status == OrderStatus.CANCELED || status == OrderStatus.COMPLETED) return PriorityLevel.NOT_APPLICABLE;
        if (status == OrderStatus.SHIPPED) return PriorityLevel.LOW;
        if (paid && total != null && total.compareTo(new BigDecimal("10000")) >= 0) return PriorityLevel.CRITICAL;
        if (paid || status == OrderStatus.PAID) return PriorityLevel.HIGH;
        if (total != null && total.compareTo(new BigDecimal("5000")) >= 0) return PriorityLevel.MEDIUM;
        return PriorityLevel.LOW;
    }
}
