package com.huuhv.foodsndrinks.service.websocket;

import com.huuhv.foodsndrinks.dto.message.OrderStatusChangedMessage;
import com.huuhv.foodsndrinks.dto.response.OrderStatusEventResDto;
import com.huuhv.foodsndrinks.enums.OrderStatus;
import com.huuhv.foodsndrinks.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Trạng thái gửi cho chủ đơn luôn đọc từ DB lúc gửi (message có thể tới sai thứ tự), chỉ tới đích theo user của chủ đơn
 * — tên trong message là username local, cũng là {@code Principal#getName()} của phiên form/remember-me/OAuth2
 * ({@code CustomOidcUserServiceTest}, {@code CustomOAuth2UserServiceTest}).
 */
@ExtendWith(MockitoExtension.class)
class OrderStatusNotifierTest {

    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private SimpUserRegistry userRegistry;
    @Mock private OrderRepository orderRepository;
    @InjectMocks private OrderStatusNotifier notifier;

    @Test
    void notifyOwner_sendsCurrentStatusFromDb_toOwnersUserQueueOnly() {
        connected("google-user");
        when(orderRepository.findStatusByIdAndOwnerUsername(42L, "google-user"))
                .thenReturn(Optional.of(OrderStatus.PROCESSING));

        notifier.notifyOwner(new OrderStatusChangedMessage(42L, "google-user", OrderStatus.PROCESSING));

        verify(messagingTemplate).convertAndSendToUser("google-user", StompDestinations.USER_ORDERS_QUEUE,
                new OrderStatusEventResDto(42L, OrderStatus.PROCESSING, OrderStatus.PROCESSING.getLabel(),
                        OrderStatus.PROCESSING.getBadgeClass()));
    }

    @Test
    void staleMessageDeliveredLate_stillSendsLatestStatus() {
        // PENDING → PROCESSING → COMPLETED: message "PROCESSING" tới sau cùng (gửi lại bởi job) — không được lùi trạng thái
        connected("alice");
        when(orderRepository.findStatusByIdAndOwnerUsername(42L, "alice")).thenReturn(Optional.of(OrderStatus.COMPLETED));

        notifier.notifyOwner(new OrderStatusChangedMessage(42L, "alice", OrderStatus.PROCESSING));

        verify(messagingTemplate).convertAndSendToUser("alice", StompDestinations.USER_ORDERS_QUEUE,
                OrderStatusEventResDto.of(42L, OrderStatus.COMPLETED));
    }

    @Test
    void ownerNotConnectedToThisInstance_skipsQueryAndSend() {
        when(userRegistry.getUser("alice")).thenReturn(null);

        notifier.notifyOwner(new OrderStatusChangedMessage(42L, "alice", OrderStatus.COMPLETED));

        verifyNoInteractions(orderRepository, messagingTemplate);
    }

    @Test
    void orderNotOwnedByUserInMessage_orBackInCart_isNotSent() {
        connected("mallory");
        when(orderRepository.findStatusByIdAndOwnerUsername(42L, "mallory")).thenReturn(Optional.empty());
        connected("alice");
        when(orderRepository.findStatusByIdAndOwnerUsername(43L, "alice")).thenReturn(Optional.of(OrderStatus.CART));

        // Message giả/nhầm chủ: đơn 42 không phải của mallory → không lộ trạng thái đơn của người khác
        notifier.notifyOwner(new OrderStatusChangedMessage(42L, "mallory", OrderStatus.COMPLETED));
        notifier.notifyOwner(new OrderStatusChangedMessage(43L, "alice", OrderStatus.PENDING));

        verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
    }

    private void connected(String username) {
        SimpUser user = mock(SimpUser.class);
        when(userRegistry.getUser(username)).thenReturn(user);
    }
}
