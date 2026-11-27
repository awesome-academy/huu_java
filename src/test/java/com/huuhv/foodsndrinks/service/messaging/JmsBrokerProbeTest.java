package com.huuhv.foodsndrinks.service.messaging;

import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import jakarta.jms.Session;
import org.junit.jupiter.api.Test;
import org.springframework.jms.connection.CachingConnectionFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JmsBrokerProbeTest {

    private final ConnectionFactory target = mock(ConnectionFactory.class);

    @Test
    void opensAFreshConnection_bypassingTheCachedOne() throws Exception {
        Connection connection = mock(Connection.class);
        Session session = mock(Session.class);
        when(target.createConnection()).thenReturn(connection);
        when(connection.createSession(false, Session.AUTO_ACKNOWLEDGE)).thenReturn(session);
        CachingConnectionFactory caching = new CachingConnectionFactory(target);
        JmsBrokerProbe probe = new JmsBrokerProbe(caching);

        assertThat(probe.reachable()).isTrue();
        assertThat(probe.reachable()).isTrue();

        // Mỗi lần kiểm tra là một kết nối thật mới tới broker (không dùng connection cache của CachingConnectionFactory)
        verify(target, times(2)).createConnection();
        verify(session, times(2)).close();
        verify(connection, times(2)).close();
    }

    @Test
    void brokerNotAnswering_isUnreachable() throws Exception {
        when(target.createConnection()).thenThrow(new JMSException("AMQ219013: Timed out waiting to receive cluster topology"));

        assertThat(new JmsBrokerProbe(new CachingConnectionFactory(target)).reachable()).isFalse();
    }
}
