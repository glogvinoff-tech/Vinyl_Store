package ru.mirea.vinylstore.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.mirea.vinylstore.dto.AnalyticsDtos.AnalyticsResponse;
import ru.mirea.vinylstore.dto.AnalyticsDtos.NamedCount;
import ru.mirea.vinylstore.repository.StoreOrderRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** Формирует компактную аналитическую сводку на стороне базы. */
@Service
public class AnalyticsService {
    private final StoreOrderRepository orders;

    public AnalyticsService(StoreOrderRepository orders) {
        this.orders = orders;
    }

    @Transactional(readOnly = true)
    public AnalyticsResponse summary() {
        long total = orders.count();
        long active = orders.countByArchivedFalse();
        long archived = orders.countByArchivedTrue();
        long paidCount = orders.countByArchivedFalseAndPaidTrue();
        BigDecimal average = orders.totalPriceForActive();
        if (average != null) average = average.setScale(2, RoundingMode.HALF_UP);
        return new AnalyticsResponse(total, active, archived, paidCount, average,
                map(orders.countActiveByRecord()), map(orders.countActiveByStatus()), map(orders.countActiveByPriority()));
    }

    private static List<NamedCount> map(List<Object[]> rows) {
        return rows.stream().map(row -> new NamedCount(String.valueOf(row[0]), ((Number) row[1]).longValue())).toList();
    }
}
