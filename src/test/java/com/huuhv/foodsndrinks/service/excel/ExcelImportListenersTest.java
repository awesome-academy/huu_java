package com.huuhv.foodsndrinks.service.excel;

import com.huuhv.foodsndrinks.config.properties.ExcelProperties;
import com.huuhv.foodsndrinks.dto.message.ExcelImportMessage;
import com.huuhv.foodsndrinks.dto.message.OrderStatusChangedMessage;
import com.huuhv.foodsndrinks.enums.OrderStatus;
import com.huuhv.foodsndrinks.service.messaging.MessageTypes;
import com.huuhv.foodsndrinks.service.messaging.OutboxService;
import jakarta.jms.JMSException;
import jakarta.jms.TextMessage;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jms.support.converter.MessageConversionException;
import org.springframework.jms.support.converter.MessageConverter;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Unit test các điểm vào chạy nền của import Excel: consumer của queue, listener DLQ và job dọn dẹp. */
@ExtendWith(OutputCaptureExtension.class)
class ExcelImportListenersTest {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll
    static void closeFactory() {
        FACTORY.close();
    }

    @Nested
    class Consumer {

        private final ImportJobProcessor processor = mock(ImportJobProcessor.class);
        private final ExcelImportConsumer consumer = new ExcelImportConsumer(processor, VALIDATOR);

        @Test
        void validMessage_isProcessed() {
            consumer.onImportRequested(new ExcelImportMessage(7L), 1);

            verify(processor).process(7L);
        }

        @Test
        void messageWithoutAUsableJobId_isDroppedInsteadOfBeingRedeliveredForever(CapturedOutput output) {
            consumer.onImportRequested(null, 1);
            consumer.onImportRequested(new ExcelImportMessage(null), 1);
            consumer.onImportRequested(new ExcelImportMessage(0L), 1);
            consumer.onImportRequested(new ExcelImportMessage(-5L), 1);

            verifyNoInteractions(processor);
            assertThat(output).contains("Dropped excel.import message without a valid importJobId");
        }

        @Test
        void processingFailure_isRethrown_soTheBrokerRedelivers(CapturedOutput output) {
            doThrow(new QueryTimeoutException("db down")).when(processor).process(7L);

            assertThatExceptionOfType(QueryTimeoutException.class)
                    .isThrownBy(() -> consumer.onImportRequested(new ExcelImportMessage(7L), 3));
            assertThat(output).contains("Import job #7 failed on delivery 3").contains("DLQ.excel-import");
        }
    }

    @Nested
    class DeadLetterQueue {

        private final MessageConverter converter = mock(MessageConverter.class);
        private final ImportJobService importJobService = mock(ImportJobService.class);
        private final OutboxService outboxService = mock(OutboxService.class);
        private final ExcelImportDlqListener listener =
                new ExcelImportDlqListener(converter, importJobService, outboxService);

        @Test
        void deadLetteredJob_isMarkedFailed_thenItsOutboxRecordIsKept(CapturedOutput output) throws Exception {
            TextMessage message = deadLetter(9L);
            when(converter.fromMessage(message)).thenReturn(new ExcelImportMessage(7L));
            when(importJobService.failDeadLettered(7L)).thenReturn(true);
            when(outboxService.markDeadLettered(9L)).thenReturn(true);

            listener.onDeadLetter(message);

            var order = inOrder(importJobService, outboxService);
            order.verify(importJobService).failDeadLettered(7L);
            order.verify(outboxService).markDeadLettered(9L);
            assertThat(output).contains("ERROR")
                    .contains("Import job #7 dead-lettered after all delivery attempts (outbox #9): marked FAILED");
        }

        @Test
        void jobThatFinishedAfterAll_isLeftUnchanged(CapturedOutput output) throws Exception {
            TextMessage message = deadLetter(9L);
            when(converter.fromMessage(message)).thenReturn(new ExcelImportMessage(7L));
            when(importJobService.failDeadLettered(7L)).thenReturn(false);
            when(outboxService.markDeadLettered(9L)).thenReturn(true);

            listener.onDeadLetter(message);

            assertThat(output).contains("Import job #7 dead-lettered").contains("already finished, left unchanged");
        }

        @Test
        void unreadableMessage_isLoggedWithoutItsContent_andNeverRethrown(CapturedOutput output) throws Exception {
            TextMessage message = deadLetter(9L);
            when(message.getText()).thenReturn("{\"importJobId\":\"secret-body\"}");
            when(converter.fromMessage(message)).thenThrow(new MessageConversionException("Unknown message type"));
            when(outboxService.markDeadLettered(9L)).thenReturn(true);

            assertThatNoException().isThrownBy(() -> listener.onDeadLetter(message));

            assertThat(output).contains("Unreadable excel.import message dead-lettered: Unknown message type")
                    .contains("outbox #9").doesNotContain("secret-body");
            verifyNoInteractions(importJobService);
            verify(outboxService).markDeadLettered(9L);
        }

        @Test
        void unexpectedPayloadOrInvalidId_marksNoJob() throws Exception {
            TextMessage other = deadLetter(null);
            when(converter.fromMessage(other))
                    .thenReturn(new OrderStatusChangedMessage(1L, "alice", OrderStatus.PENDING));
            TextMessage invalid = deadLetter(null);
            when(converter.fromMessage(invalid)).thenReturn(new ExcelImportMessage(0L));

            listener.onDeadLetter(other);
            listener.onDeadLetter(invalid);

            verify(importJobService, never()).failDeadLettered(anyLong());
            // Message không đi qua outbox → không có bản ghi nào để đánh dấu
            verifyNoInteractions(outboxService);
        }

        @Test
        void databaseFailure_isRethrown_soTheBrokerRedeliversTheDeadLetter() throws Exception {
            TextMessage message = deadLetter(9L);
            when(converter.fromMessage(message)).thenReturn(new ExcelImportMessage(7L));
            when(importJobService.failDeadLettered(7L)).thenThrow(new QueryTimeoutException("db down"));

            assertThatExceptionOfType(QueryTimeoutException.class).isThrownBy(() -> listener.onDeadLetter(message));
            verifyNoInteractions(outboxService);
        }

        private static TextMessage deadLetter(Long outboxId) throws JMSException {
            TextMessage message = mock(TextMessage.class);
            when(message.propertyExists(MessageTypes.OUTBOX_ID_PROPERTY)).thenReturn(outboxId != null);
            if (outboxId != null) {
                when(message.getLongProperty(MessageTypes.OUTBOX_ID_PROPERTY)).thenReturn(outboxId);
            }
            return message;
        }
    }

    @Nested
    class CleanupJob {

        private final ImportJobService importJobService = mock(ImportJobService.class);
        private final ImportFileStorage storage = mock(ImportFileStorage.class);
        private final ExcelProperties properties = ExcelTestFiles.properties();
        private final ImportJobCleanupJob job = new ImportJobCleanupJob(importJobService, storage, properties);

        @Test
        void failStaleJobs_delegatesToTheService_andNeverLetsAnErrorEscapeTheScheduler(CapturedOutput output) {
            when(importJobService.failStale()).thenReturn(2).thenThrow(new QueryTimeoutException("db down"));

            job.failStaleJobs();
            assertThatNoException().isThrownBy(job::failStaleJobs);

            assertThat(output).contains("Marked 2 unfinished import job(s) as FAILED")
                    .contains("Failing stale import jobs failed, will retry next cycle");
        }

        @Test
        void deleteOrphanFiles_onlyRemovesFilesOlderThanTwiceTheStaleAge(CapturedOutput output) {
            // Lúc đó lần import của file chắc chắn đã kết thúc (bị đánh dấu thất bại sau stale-after)
            when(storage.deleteOlderThan(Duration.ofMinutes(60))).thenReturn(3);

            job.deleteOrphanFiles();

            verify(storage).deleteOlderThan(Duration.ofMinutes(60));
            assertThat(output).contains("Deleted 3 leftover import file(s)");
        }
    }
}
