package ru.mirea.vinylstore.service;
import org.junit.jupiter.api.Test;
import ru.mirea.vinylstore.domain.*;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;
class PriorityCalculatorTest {
    private final PriorityCalculator c=new PriorityCalculator();
    @Test void completedAndCanceledHaveNoProcessingPriority() {
        assertThat(c.calculate(OrderStatus.COMPLETED,new BigDecimal("20000"),true)).isEqualTo(PriorityLevel.NOT_APPLICABLE);
        assertThat(c.calculate(OrderStatus.CANCELED,new BigDecimal("20000"),true)).isEqualTo(PriorityLevel.NOT_APPLICABLE);
    }
    @Test void prioritizesLargePaidOrders() {
        assertThat(c.calculate(OrderStatus.PAID,new BigDecimal("10000"),true)).isEqualTo(PriorityLevel.CRITICAL);
        assertThat(c.calculate(OrderStatus.PAID,new BigDecimal("9999.99"),true)).isEqualTo(PriorityLevel.HIGH);
        assertThat(c.calculate(OrderStatus.NEW,new BigDecimal("5000"),false)).isEqualTo(PriorityLevel.MEDIUM);
        assertThat(c.calculate(OrderStatus.NEW,new BigDecimal("4999.99"),false)).isEqualTo(PriorityLevel.LOW);
        assertThat(c.calculate(OrderStatus.SHIPPED,new BigDecimal("20000"),true)).isEqualTo(PriorityLevel.LOW);
    }
}
