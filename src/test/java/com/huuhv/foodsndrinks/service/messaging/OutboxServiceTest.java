package com.huuhv.foodsndrinks.service.messaging;

import com.huuhv.foodsndrinks.dto.message.OrderPlacedMessage;
import com.huuhv.foodsndrinks.entity.OutboxMessage;
import com.huuhv.foodsndrinks.enums.OutboxStatus;
import com.huuhv.foodsndrinks.repository.OutboxMessageRepository;
import com.huuhv.foodsndrinks.service.messaging.OutboxService.OutboxMessageSavedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxServiceTest {

    @Mock private OutboxMessageRepository repository;
    @Mock private ApplicationEventPublisher eventPublisher;

    @Test
    void enqueue_storesAliasAndJsonPayload_thenAnnouncesTheRow() {
        OutboxService service = new OutboxService(repository, JsonMapper.builder().build(), eventPublisher);
        when(repository.save(any())).thenAnswer(invocation -> {
            OutboxMessage row = invocation.getArgument(0);
            row.setId(99L);
            return row;
        });

        service.enqueue(OutboxType.ORDER_PLACED, new OrderPlacedMessage(42L, "A", "a@example.com", null, "HN", null,
                new BigDecimal("120000"), null, List.of()));

        ArgumentCaptor<OutboxMessage> saved = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getType()).isEqualTo("orderPlaced");
        assertThat(saved.getValue().getPayload()).contains("\"orderId\":42").contains("\"totalPrice\":120000");
        verify(eventPublisher).publishEvent(new OutboxMessageSavedEvent(99L));
    }

    @Test
    void enqueue_rejectsPayloadOfAnotherType() {
        OutboxService service = new OutboxService(repository, JsonMapper.builder().build(), eventPublisher);

        assertThatIllegalArgumentException().isThrownBy(() -> service.enqueue(OutboxType.ORDER_PLACED, "not a DTO"));
        verifyNoInteractions(repository, eventPublisher);
    }

    @Test
    void markDeadLettered_sentRecord_isMarked() {
        OutboxService service = new OutboxService(repository, JsonMapper.builder().build(), eventPublisher);
        when(repository.markDeadLettered(9L)).thenReturn(1);

        assertThat(service.markDeadLettered(9L)).isTrue();
    }

    @Test
    void markDeadLettered_alreadyMarkedByAnotherSubscription_countsAsDone() {
        // Cùng message vào DLQ từ cả subscription email lẫn slack → lần thứ hai bản ghi đã DEAD_LETTERED
        OutboxService service = new OutboxService(repository, JsonMapper.builder().build(), eventPublisher);
        when(repository.markDeadLettered(9L)).thenReturn(0);
        when(repository.findStatusById(9L)).thenReturn(Optional.of(OutboxStatus.DEAD_LETTERED));

        assertThat(service.markDeadLettered(9L)).isTrue();
    }

    @Test
    void markDeadLettered_resetOrPurgedRecord_isReported() {
        OutboxService service = new OutboxService(repository, JsonMapper.builder().build(), eventPublisher);
        when(repository.markDeadLettered(anyLong())).thenReturn(0);
        when(repository.findStatusById(9L)).thenReturn(Optional.of(OutboxStatus.PENDING));
        when(repository.findStatusById(10L)).thenReturn(Optional.empty());

        assertThat(service.markDeadLettered(9L)).isFalse();
        assertThat(service.markDeadLettered(10L)).isFalse();
    }

    @Test
    void purgeSent_deletesThroughRepository_withRetentionInSeconds() {
        OutboxService service = new OutboxService(repository, JsonMapper.builder().build(), eventPublisher);
        when(repository.deleteSentBefore(604_800L, 1000)).thenReturn(3);

        assertThat(service.purgeSent(Duration.ofDays(7), 1000)).isEqualTo(3);
    }

    @Test
    void purgeAndDeadLetterMarking_runAtReadCommitted_soTheyTakeNoGapLocksThatBlockCheckout() throws Exception {
        // REPEATABLE READ: DELETE/UPDATE theo khoảng trên (status, sent_at) giữ gap lock, chặn INSERT outbox của checkout
        for (Method method : List.of(
                OutboxService.class.getMethod("purgeSent", Duration.class, int.class),
                OutboxService.class.getMethod("markDeadLettered", long.class))) {
            assertThat(method.getAnnotation(Transactional.class).isolation())
                    .as(method.getName()).isEqualTo(Isolation.READ_COMMITTED);
        }
    }

    @Test
    void everyOutboxType_isInTheConverterWhitelist() {
        for (OutboxType type : OutboxType.values()) {
            assertThat(MessageTypes.TYPE_ID_MAPPINGS).containsEntry(type.alias(), type.payloadType());
            assertThat(OutboxType.fromAlias(type.alias())).contains(type);
        }
        assertThat(OutboxType.fromAlias("unknown")).isEmpty();
    }
}
