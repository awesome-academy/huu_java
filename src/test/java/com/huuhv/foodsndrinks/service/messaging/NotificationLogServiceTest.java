package com.huuhv.foodsndrinks.service.messaging;

import com.huuhv.foodsndrinks.enums.NotificationChannel;
import com.huuhv.foodsndrinks.exception.NotificationDeliveryException;
import com.huuhv.foodsndrinks.repository.NotificationLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationLogServiceTest {

    @Mock private NotificationLogRepository repository;
    @Mock private PlatformTransactionManager transactionManager;

    private NotificationLogService service;
    private final AtomicInteger sends = new AtomicInteger();

    @BeforeEach
    void setUp() {
        service = new NotificationLogService(repository, transactionManager);
    }

    @Test
    void firstDelivery_recordsThenSends_insideOneTransaction() {
        when(repository.insertIfAbsent(42L, "SLACK")).thenReturn(1);

        boolean sent = service.sendOnce(42L, NotificationChannel.SLACK, sends::incrementAndGet);

        assertThat(sent).isTrue();
        assertThat(sends).hasValue(1);
        verify(transactionManager).commit(any());
    }

    @Test
    void alreadyRecorded_skipsSending() {
        when(repository.insertIfAbsent(42L, "EMAIL")).thenReturn(0);

        boolean sent = service.sendOnce(42L, NotificationChannel.EMAIL, sends::incrementAndGet);

        assertThat(sent).isFalse();
        assertThat(sends).hasValue(0);
    }

    @Test
    void sendFailure_rollsBackTheRecord_andPropagates() {
        when(repository.insertIfAbsent(42L, "SLACK")).thenReturn(1);

        assertThatThrownBy(() -> service.sendOnce(42L, NotificationChannel.SLACK, () -> {
            throw new NotificationDeliveryException("webhook down");
        })).isInstanceOf(NotificationDeliveryException.class);

        verify(transactionManager).rollback(any());
    }

    @Test
    void lockConflictBeforeSending_isRetriedOnce() {
        when(repository.insertIfAbsent(42L, "SLACK"))
                .thenThrow(new CannotAcquireLockException("deadlock"))
                .thenReturn(1);

        boolean sent = service.sendOnce(42L, NotificationChannel.SLACK, sends::incrementAndGet);

        assertThat(sent).isTrue();
        assertThat(sends).hasValue(1);
        verify(repository, times(2)).insertIfAbsent(42L, "SLACK");
    }

    @Test
    void lockConflictAfterSending_isNotRetried_soNotificationIsNotSentTwice() {
        when(repository.insertIfAbsent(42L, "SLACK")).thenReturn(1);

        assertThatThrownBy(() -> service.sendOnce(42L, NotificationChannel.SLACK, () -> {
            sends.incrementAndGet();
            throw new CannotAcquireLockException("deadlock at commit");
        })).isInstanceOf(CannotAcquireLockException.class);

        assertThat(sends).hasValue(1);
        verify(repository, times(1)).insertIfAbsent(42L, "SLACK");
    }
}
