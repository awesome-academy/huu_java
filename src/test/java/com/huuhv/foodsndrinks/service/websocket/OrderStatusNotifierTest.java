package com.huuhv.foodsndrinks.service.websocket;

import com.huuhv.foodsndrinks.dto.message.OrderStatusChangedMessage;
import com.huuhv.foodsndrinks.dto.response.OrderStatusEventResDto;
import com.huuhv.foodsndrinks.enums.OrderStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * Trạng thái mới chỉ gửi tới đích theo user của chủ đơn — tên trong message là username local, cũng là
 * {@code Principal#getName()} của phiên form/remember-me/OAuth2 ({@code CustomOidcUserServiceTest},
 * {@code CustomOAuth2UserServiceTest}).
 */
@ExtendWith(MockitoExtension.class)
class OrderStatusNotifierTest {

    @Mock private SimpMessagingTemplate messagingTemplate;
    @InjectMocks private OrderStatusNotifier notifier;

    @Test
    void notifyOwner_sendsStatusLabelAndBadge_toOwnersUserQueueOnly() {
        notifier.notifyOwner(new OrderStatusChangedMessage(42L, "google-user", OrderStatus.PROCESSING));

        verify(messagingTemplate).convertAndSendToUser("google-user", StompDestinations.USER_ORDERS_QUEUE,
                new OrderStatusEventResDto(42L, OrderStatus.PROCESSING, OrderStatus.PROCESSING.getLabel(),
                        OrderStatus.PROCESSING.getBadgeClass()));
        verifyNoMoreInteractions(messagingTemplate);
    }
}
