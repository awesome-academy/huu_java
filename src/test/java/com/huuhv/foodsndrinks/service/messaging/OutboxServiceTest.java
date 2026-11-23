package com.huuhv.foodsndrinks.service.messaging;

import com.huuhv.foodsndrinks.dto.message.OrderPlacedMessage;
import com.huuhv.foodsndrinks.entity.OutboxMessage;
import com.huuhv.foodsndrinks.repository.OutboxMessageRepository;
import com.huuhv.foodsndrinks.service.messaging.OutboxService.OutboxMessageSavedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
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
    void everyOutboxType_isInTheConverterWhitelist() {
        for (OutboxType type : OutboxType.values()) {
            assertThat(MessageTypes.TYPE_ID_MAPPINGS).containsEntry(type.alias(), type.payloadType());
            assertThat(OutboxType.fromAlias(type.alias())).contains(type);
        }
        assertThat(OutboxType.fromAlias("unknown")).isEmpty();
    }
}
