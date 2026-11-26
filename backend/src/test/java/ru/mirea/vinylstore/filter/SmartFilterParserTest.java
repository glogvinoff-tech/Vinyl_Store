package ru.mirea.vinylstore.filter;
import org.junit.jupiter.api.Test;
import ru.mirea.vinylstore.domain.*;
import ru.mirea.vinylstore.error.BadRequestException;
import static org.assertj.core.api.Assertions.*;
class SmartFilterParserTest {
    private final SmartFilterParser parser=new SmartFilterParser();
    @Test void parsesOrderFieldsAndMoney() {
        var f=parser.parse("Pink Floyd пластинка:LP-001 сумма>=3000,50 оплата:да приоритет:высокий тег:доставка архив:все статус:оплачен");
        assertThat(f.freeText()).isEqualTo("Pink Floyd");assertThat(f.record()).isEqualTo("LP-001");
        assertThat(f.paid()).isTrue();assertThat(f.priority()).isEqualTo(PriorityLevel.HIGH);
        assertThat(f.status()).isEqualTo(OrderStatus.PAID);assertThat(f.tag()).isEqualTo("доставка");
        assertThat(f.archived()).isNull();assertThat(f.prices()).hasSize(1);
        assertThat(f.prices().getFirst().value()).isEqualByComparingTo("3000.50");
    }
    @Test void rejectsUnknownStatusAndOversizedQuery() {
        assertThatThrownBy(()->parser.parse("status:invalid")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(()->parser.parse("x".repeat(501))).isInstanceOf(BadRequestException.class);
    }
    @Test void injectionRemainsPlainSearchText() {
        var f=parser.parse("' OR 1=1 --");assertThat(f.freeText()).isEqualTo("' OR 1=1 --");assertThat(f.archived()).isFalse();
    }
}
