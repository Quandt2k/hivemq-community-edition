/*
 * Copyright 2019 dc-square GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hivemq.mqtt.handler.connect;

import com.codahale.metrics.MetricRegistry;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.SettableFuture;
import com.hivemq.bootstrap.netty.ChannelDependencies;
import com.hivemq.bootstrap.netty.ChannelHandlerNames;
import com.hivemq.configuration.service.FullConfigurationService;
import com.hivemq.extension.sdk.api.auth.SimpleAuthenticator;
import com.hivemq.extension.sdk.api.auth.parameter.TopicPermission;
import com.hivemq.extension.sdk.api.packets.auth.DefaultAuthorizationBehaviour;
import com.hivemq.extension.sdk.api.packets.disconnect.DisconnectReasonCode;
import com.hivemq.extension.sdk.api.packets.publish.AckReasonCode;
import com.hivemq.extensions.classloader.IsolatedPluginClassloader;
import com.hivemq.extensions.client.parameter.AuthenticatorProviderInputFactory;
import com.hivemq.extensions.events.OnServerDisconnectEvent;
import com.hivemq.extensions.executor.PluginOutPutAsyncer;
import com.hivemq.extensions.executor.PluginTaskExecutorService;
import com.hivemq.extensions.handler.PluginAuthorizerService;
import com.hivemq.extensions.handler.PluginAuthorizerServiceImpl.AuthorizeWillResultEvent;
import com.hivemq.extensions.handler.tasks.PublishAuthorizerResult;
import com.hivemq.extensions.packets.general.ModifiableDefaultPermissionsImpl;
import com.hivemq.extensions.services.auth.Authenticators;
import com.hivemq.extensions.services.auth.Authorizers;
import com.hivemq.extensions.services.auth.ModifiableClientSettingsImpl;
import com.hivemq.extensions.services.auth.WrappedAuthenticatorProvider;
import com.hivemq.extensions.services.builder.TopicPermissionBuilderImpl;
import com.hivemq.limitation.TopicAliasLimiterImpl;
import com.hivemq.logging.EventLog;
import com.hivemq.metrics.MetricsHolder;
import com.hivemq.mqtt.handler.auth.AuthInProgressMessageHandler;
import com.hivemq.mqtt.handler.connack.MqttConnackSendUtil;
import com.hivemq.mqtt.handler.connack.MqttConnacker;
import com.hivemq.mqtt.handler.disconnect.Mqtt5ServerDisconnector;
import com.hivemq.mqtt.handler.disconnect.MqttDisconnectUtil;
import com.hivemq.mqtt.handler.ordering.OrderedTopicHandler;
import com.hivemq.mqtt.handler.publish.FlowControlHandler;
import com.hivemq.mqtt.message.MessageIDPools;
import com.hivemq.mqtt.message.ProtocolVersion;
import com.hivemq.mqtt.message.QoS;
import com.hivemq.mqtt.message.connack.CONNACK;
import com.hivemq.mqtt.message.connack.Mqtt3ConnAckReturnCode;
import com.hivemq.mqtt.message.connect.CONNECT;
import com.hivemq.mqtt.message.connect.Mqtt5CONNECT;
import com.hivemq.mqtt.message.connect.MqttWillPublish;
import com.hivemq.mqtt.message.mqtt5.Mqtt5UserProperties;
import com.hivemq.mqtt.message.mqtt5.MqttUserProperty;
import com.hivemq.mqtt.message.reason.Mqtt5ConnAckReasonCode;
import com.hivemq.mqtt.message.subscribe.Topic;
import com.hivemq.mqtt.services.PublishPollService;
import com.hivemq.persistence.ChannelPersistence;
import com.hivemq.persistence.SingleWriterService;
import com.hivemq.persistence.clientsession.ClientSessionPersistence;
import com.hivemq.persistence.clientsession.ClientSessionSubscriptionPersistence;
import com.hivemq.persistence.clientsession.SharedSubscriptionService;
import com.hivemq.util.ChannelAttributes;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import util.*;

import javax.inject.Provider;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.hivemq.extension.sdk.api.auth.parameter.OverloadProtectionThrottlingLevel.*;
import static org.junit.Assert.*;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.*;

@SuppressWarnings("NullabilityAnnotations")
public class ConnectHandlerTest {

    private EmbeddedChannel embeddedChannel;

    @Rule
    public InitFutureUtilsExecutorRule initFutureUtilsExecutorRule = new InitFutureUtilsExecutorRule();

    @Mock
    private ClientSessionPersistence clientSessionPersistence;

    @Mock
    private ChannelPersistence channelPersistence;

    @Mock
    private EventLog eventLog;

    @Mock
    private ChannelDependencies channelDependencies;

    @Mock
    private Authenticators authenticators;

    @Mock
    private Authorizers authorizers;

    @Mock
    private PluginTaskExecutorService pluginTaskExecutorService;

    @Mock
    private IsolatedPluginClassloader isolatedPluginClassloader;
    @Mock
    private IsolatedPluginClassloader isolatedPluginClassloader2;
    @Mock
    private IsolatedPluginClassloader isolatedPluginClassloader3;
    @Mock
    private PluginAuthorizerService pluginAuthorizerService;

    private FullConfigurationService configurationService;
    private MqttConnacker mqttConnacker;
    private ChannelHandlerContext ctx;
    private ConnectHandler handler;
    private MetricsHolder metricsHolder;
    private final SingleWriterService singleWriterService = TestSingleWriterFactory.defaultSingleWriter();

    @Before
    public void setUp() throws Exception {

        MockitoAnnotations.initMocks(this);
        when(clientSessionPersistence.isExistent(anyString())).thenReturn(false);
        when(clientSessionPersistence.clientConnected(anyString(), anyBoolean(), anyInt(), any(MqttWillPublish.class))).thenReturn(Futures.immediateFuture(null));

        metricsHolder = new MetricsHolder(new MetricRegistry());
        embeddedChannel = new EmbeddedChannel(new DummyHandler());

        configurationService = new TestConfigurationBootstrap().getFullConfigurationService();
        final MqttConnackSendUtil connackSendUtil = new MqttConnackSendUtil(eventLog, configurationService.mqttConfiguration());
        mqttConnacker = new MqttConnacker(connackSendUtil);

        when(channelPersistence.get(anyString())).thenReturn(null);

        when(channelDependencies.getAuthInProgressMessageHandler()).thenReturn(
                new AuthInProgressMessageHandler(mqttConnacker));

        buildPipeline();
    }

    @Test
    public void test_disconnect_after_second_connect_message() {

        final CONNECT connect1 = new CONNECT.Mqtt3Builder().withProtocolVersion(ProtocolVersion.MQTTv3_1_1)
                .withClientIdentifier("1")
                .build();
        final CONNECT connect2 = new CONNECT.Mqtt3Builder().withProtocolVersion(ProtocolVersion.MQTTv3_1_1)
                .withClientIdentifier("2")
                .build();

        assertEquals(true, embeddedChannel.isOpen());
        embeddedChannel.writeInbound(connect1);
        assertEquals(true, embeddedChannel.isOpen());

        // no need to check ordered topic handler in this test so we set the disconnect future immediately by expiry null
        embeddedChannel.attr(ChannelAttributes.CLIENT_SESSION_EXPIRY_INTERVAL).set(null);

        embeddedChannel.writeInbound(connect2);

        //We were disconnected after the second CONNECT message
        assertEquals(false, embeddedChannel.isOpen());
    }

    @Test
    public void test_disconnect_after_second_connect_message_mqtt5() {

        final CONNECT connect1 = new CONNECT.Mqtt5Builder().withClientIdentifier("1")
                .withMqtt5UserProperties(Mqtt5UserProperties.NO_USER_PROPERTIES)
                .build();
        final CONNECT connect2 = new CONNECT.Mqtt5Builder().withClientIdentifier("2")
                .withMqtt5UserProperties(Mqtt5UserProperties.NO_USER_PROPERTIES)
                .build();

        assertEquals(true, embeddedChannel.isOpen());
        embeddedChannel.writeInbound(connect1);
        assertEquals(true, embeddedChannel.isOpen());

        // no need to check ordered topic handler in this test so we set the disconnect future immediately by expiry null
        embeddedChannel.attr(ChannelAttributes.CLIENT_SESSION_EXPIRY_INTERVAL).set(null);

        embeddedChannel.writeInbound(connect2);

        //We were disconnected after the second CONNECT message
        assertEquals(false, embeddedChannel.isOpen());
    }

    @Test
    public void test_connect_with_session_expiry_interval_zero() {

        final CONNECT connect1 = new CONNECT.Mqtt5Builder().withSessionExpiryInterval(0)
                .withClientIdentifier("1")
                .withMqtt5UserProperties(Mqtt5UserProperties.NO_USER_PROPERTIES)
                .build();

        assertEquals(true, embeddedChannel.isOpen());
        embeddedChannel.writeInbound(connect1);
        assertEquals(true, embeddedChannel.isOpen());

        final Long expiry = embeddedChannel.attr(ChannelAttributes.CLIENT_SESSION_EXPIRY_INTERVAL).get();

        assertNotNull(expiry);
        assertEquals(0, expiry.longValue());
    }

    @Test
    public void test_connect_with_keep_alive_zero() {

        final CONNECT connect1 = new CONNECT.Mqtt5Builder().withKeepAlive(0)
                .withClientIdentifier("1")
                .withMqtt5UserProperties(Mqtt5UserProperties.NO_USER_PROPERTIES)
                .build();

        assertEquals(true, embeddedChannel.isOpen());
        embeddedChannel.writeInbound(connect1);
        assertEquals(true, embeddedChannel.isOpen());

        final Integer keepAlive = embeddedChannel.attr(ChannelAttributes.CONNECT_KEEP_ALIVE).get();

        assertNotNull(keepAlive);
        assertEquals(0, keepAlive.longValue());
    }

    @Test
    public void test_connect_with_keep_alive_zero_not_allowed() {

        configurationService.mqttConfiguration().setKeepAliveMax(65535);
        configurationService.mqttConfiguration().setKeepAliveAllowZero(false);

        createHandler();

        final CONNECT connect1 = new CONNECT.Mqtt5Builder().withKeepAlive(0)
                .withClientIdentifier("1")
                .withMqtt5UserProperties(Mqtt5UserProperties.NO_USER_PROPERTIES)
                .build();

        assertEquals(true, embeddedChannel.isOpen());
        embeddedChannel.writeInbound(connect1);
        assertEquals(true, embeddedChannel.isOpen());

        final Integer keepAlive = embeddedChannel.attr(ChannelAttributes.CONNECT_KEEP_ALIVE).get();

        assertNotNull(keepAlive);
        assertEquals(65535, keepAlive.longValue());
    }

    @Test
    public void test_connect_with_keep_alive_higher_than_server() {

        configurationService.mqttConfiguration().setKeepAliveMax(500);
        configurationService.mqttConfiguration().setKeepAliveAllowZero(false);

        createHandler();

        final CONNECT connect1 = new CONNECT.Mqtt5Builder().withKeepAlive(1000)
                .withClientIdentifier("1")
                .withMqtt5UserProperties(Mqtt5UserProperties.NO_USER_PROPERTIES)
                .build();

        final AtomicLong keepAliveFromCONNACK = new AtomicLong();
        final CountDownLatch connackLatch = new CountDownLatch(1);

        embeddedChannel.pipeline().addFirst(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise)
                    throws Exception {
                if (msg instanceof CONNACK) {
                    keepAliveFromCONNACK.set(((CONNACK) msg).getServerKeepAlive());
                    connackLatch.countDown();
                }
                super.write(ctx, msg, promise);
            }
        });


        assertEquals(true, embeddedChannel.isOpen());
        embeddedChannel.writeInbound(connect1);
        assertEquals(true, embeddedChannel.isOpen());

        final Integer keepAlive = embeddedChannel.attr(ChannelAttributes.CONNECT_KEEP_ALIVE).get();

        assertNotNull(keepAlive);
        assertEquals(500, keepAlive.longValue());
        assertEquals(500, keepAliveFromCONNACK.get());
    }

    @Test
    public void test_connect_with_keep_alive_ok() {

        configurationService.mqttConfiguration().setKeepAliveMax(500);
        configurationService.mqttConfiguration().setKeepAliveAllowZero(false);

        createHandler();

        final CONNECT connect1 = new CONNECT.Mqtt5Builder().withKeepAlive(360)
                .withClientIdentifier("1")
                .withMqtt5UserProperties(Mqtt5UserProperties.NO_USER_PROPERTIES)
                .build();

        final AtomicLong keepAliveFromCONNACK = new AtomicLong();
        final CountDownLatch connackLatch = new CountDownLatch(1);

        embeddedChannel.pipeline().addFirst(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise)
                    throws Exception {
                if (msg instanceof CONNACK) {
                    keepAliveFromCONNACK.set(((CONNACK) msg).getServerKeepAlive());
                    connackLatch.countDown();
                }
                super.write(ctx, msg, promise);
            }
        });


        assertEquals(true, embeddedChannel.isOpen());
        embeddedChannel.writeInbound(connect1);
        assertEquals(true, embeddedChannel.isOpen());

        final Integer keepAlive = embeddedChannel.attr(ChannelAttributes.CONNECT_KEEP_ALIVE).get();

        assertNotNull(keepAlive);
        assertEquals(360, keepAlive.longValue());
        assertEquals(Mqtt5CONNECT.KEEP_ALIVE_NOT_SET, keepAliveFromCONNACK.get());
    }

    @Test
    public void test_connect_with_max_packet_size() {

        final CONNECT connect1 = new CONNECT.Mqtt5Builder().withMaximumPacketSize(300)
                .withClientIdentifier("1")
                .withMqtt5UserProperties(Mqtt5UserProperties.NO_USER_PROPERTIES)
                .build();

        assertEquals(true, embeddedChannel.isOpen());
        embeddedChannel.writeInbound(connect1);
        assertEquals(true, embeddedChannel.isOpen());

        final Long maximumPacketSize = embeddedChannel.attr(ChannelAttributes.MAX_PACKET_SIZE_SEND).get();

        assertNotNull(maximumPacketSize);
        assertEquals(300, maximumPacketSize.longValue());
    }

    @Test
    public void test_connect_with_session_expiry_interval_max() {

        configurationService.mqttConfiguration().setMaxSessionExpiryInterval(Mqtt5CONNECT.SESSION_EXPIRY_MAX);

        createHandler();

        final CONNECT connect1 = new CONNECT.Mqtt5Builder().withSessionExpiryInterval(Mqtt5CONNECT.SESSION_EXPIRY_MAX)
                .withClientIdentifier("1")
                .withMqtt5UserProperties(Mqtt5UserProperties.NO_USER_PROPERTIES)
                .build();

        assertEquals(true, embeddedChannel.isOpen());
        embeddedChannel.writeInbound(connect1);
        assertEquals(true, embeddedChannel.isOpen());

        final Long expiry = embeddedChannel.attr(ChannelAttributes.CLIENT_SESSION_EXPIRY_INTERVAL).get();

        assertNotNull(expiry);
        assertEquals(Mqtt5CONNECT.SESSION_EXPIRY_MAX, expiry.longValue());
    }

    @Test
    public void test_connect_with_topic_alias_enabled() {

        configurationService.mqttConfiguration().setTopicAliasMaxPerClient(5);
        configurationService.mqttConfiguration().setTopicAliasEnabled(true);

        createHandler();

        final CONNECT connect1 = new CONNECT.Mqtt5Builder().withSessionExpiryInterval(Mqtt5CONNECT.SESSION_EXPIRY_MAX)
                .withClientIdentifier("1")
                .withMqtt5UserProperties(Mqtt5UserProperties.NO_USER_PROPERTIES)
                .build();

        assertEquals(true, embeddedChannel.isOpen());
        embeddedChannel.writeInbound(connect1);
        assertEquals(true, embeddedChannel.isOpen());

        final String[] mapping = embeddedChannel.attr(ChannelAttributes.TOPIC_ALIAS_MAPPING).get();

        assertEquals(5, mapping.length);
    }

    @Test
    public void test_connect_with_topic_alias_disabled() {

        configurationService.mqttConfiguration().setTopicAliasMaxPerClient(5);
        configurationService.mqttConfiguration().setTopicAliasEnabled(false);

        createHandler();

        final CONNECT connect1 = new CONNECT.Mqtt5Builder().withSessionExpiryInterval(Mqtt5CONNECT.SESSION_EXPIRY_MAX)
                .withClientIdentifier("1")
                .withMqtt5UserProperties(Mqtt5UserProperties.NO_USER_PROPERTIES)
                .build();

        assertEquals(true, embeddedChannel.isOpen());
        embeddedChannel.writeInbound(connect1);
        assertEquals(true, embeddedChannel.isOpen());

        final String[] mapping = embeddedChannel.attr(ChannelAttributes.TOPIC_ALIAS_MAPPING).get();
        assertNull(mapping);

    }

    @Test
    public void test_connect_with_session_expiry_interval_overridden() {

        configurationService.mqttConfiguration().setMaxSessionExpiryInterval(10000L);

        createHandler();

        final CONNECT connect1 = new CONNECT.Mqtt5Builder().withSessionExpiryInterval(Mqtt5CONNECT.SESSION_EXPIRY_MAX)
                .withClientIdentifier("1")
                .withMqtt5UserProperties(Mqtt5UserProperties.NO_USER_PROPERTIES)
                .build();


        final AtomicLong sessionExpiryFromCONNACK = new AtomicLong();
        final CountDownLatch connackLatch = new CountDownLatch(1);

        embeddedChannel.pipeline().addFirst(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise)
                    throws Exception {
                if (msg instanceof CONNACK) {
                    sessionExpiryFromCONNACK.set(((CONNACK) msg).getSessionExpiryInterval());
                    connackLatch.countDown();
                }
                super.write(ctx, msg, promise);
            }
        });

        assertEquals(true, embeddedChannel.isOpen());
        embeddedChannel.writeInbound(connect1);
        assertEquals(true, embeddedChannel.isOpen());

        final Long expiryFromChannel = embeddedChannel.attr(ChannelAttributes.CLIENT_SESSION_EXPIRY_INTERVAL).get();

        assertNotNull(expiryFromChannel);
        assertEquals(10000L, expiryFromChannel.longValue());
        assertEquals(10000L, sessionExpiryFromCONNACK.get());
    }

    @Test
    public void test_connect_with_assigned_client_identifier() throws InterruptedException {

        configurationService.securityConfiguration().setAllowServerAssignedClientId(true);

        createHandler();

        embeddedChannel.attr(ChannelAttributes.CLIENT_ID_ASSIGNED).set(true);

        final CONNECT connect1 = new CONNECT.Mqtt5Builder().withClientIdentifier("assigned")
                .withMqtt5UserProperties(Mqtt5UserProperties.NO_USER_PROPERTIES)
                .build();

        final AtomicReference<String> clientID = new AtomicReference<>();
        final CountDownLatch connackLatch = new CountDownLatch(1);

        embeddedChannel.pipeline().addFirst(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise)
                    throws Exception {
                if (msg instanceof CONNACK) {
                    clientID.set(((CONNACK) msg).getAssignedClientIdentifier());
                    connackLatch.countDown();
                }
                super.write(ctx, msg, promise);
            }
        });

        assertEquals(true, embeddedChannel.isOpen());
        embeddedChannel.writeInbound(connect1);
        assertEquals(true, embeddedChannel.isOpen());

        assertTrue(connackLatch.await(10, TimeUnit.SECONDS));

        assertEquals("assigned", clientID.get());
    }

    @Test
    public void test_connect_with_own_client_identifier() throws InterruptedException {

        configurationService.securityConfiguration().setAllowServerAssignedClientId(true);

        createHandler();

        embeddedChannel.attr(ChannelAttributes.CLIENT_ID_ASSIGNED).set(false);

        final CONNECT connect1 = new CONNECT.Mqtt5Builder().withClientIdentifier("ownId")
                .withMqtt5UserProperties(Mqtt5UserProperties.NO_USER_PROPERTIES)
                .build();

        final AtomicReference<String> clientID = new AtomicReference<>();
        final CountDownLatch connackLatch = new CountDownLatch(1);

        embeddedChannel.pipeline().addFirst(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise)
                    throws Exception {
                if (msg instanceof CONNACK) {
                    clientID.set(((CONNACK) msg).getAssignedClientIdentifier());
                    connackLatch.countDown();
                }
                super.write(ctx, msg, promise);
            }
        });

        assertEquals(true, embeddedChannel.isOpen());
        embeddedChannel.writeInbound(connect1);
        assertEquals(true, embeddedChannel.isOpen());

        assertTrue(connackLatch.await(10, TimeUnit.SECONDS));

        assertEquals(null, clientID.get());
    }

    @Test
    public void test_connect_with_auth_user_props() throws InterruptedException {

        configurationService.securityConfiguration().setAllowServerAssignedClientId(true);

        createHandler();

        embeddedChannel.attr(ChannelAttributes.AUTH_USER_PROPERTIES)
                .set(Mqtt5UserProperties.of(MqttUserProperty.of("name", "value")));

        final CONNECT connect1 = new CONNECT.Mqtt5Builder().withClientIdentifier("ownId")
                .withMqtt5UserProperties(Mqtt5UserProperties.of(MqttUserProperty.of("connect", "value")))
                .build();

        final AtomicReference<Mqtt5UserProperties> userProps = new AtomicReference<>();
        final CountDownLatch connackLatch = new CountDownLatch(1);

        embeddedChannel.pipeline().addFirst(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise)
                    throws Exception {
                if (msg instanceof CONNACK) {
                    userProps.set(((CONNACK) msg).getUserProperties());
                    connackLatch.countDown();
                }
                super.write(ctx, msg, promise);
            }
        });

        assertEquals(true, embeddedChannel.isOpen());
        embeddedChannel.writeInbound(connect1);
        assertEquals(true, embeddedChannel.isOpen());

        assertTrue(connackLatch.await(10, TimeUnit.SECONDS));

        assertEquals(1, userProps.get().asList().size());
        assertEquals("name", userProps.get().asList().get(0).getName());
        assertEquals("value", userProps.get().asList().get(0).getValue());

        assertEquals(null, embeddedChannel.attr(ChannelAttributes.AUTH_USER_PROPERTIES).get());
    }

    @Test
    public void test_connect_handler_removed_from_pipeline() {

        System.out.println(embeddedChannel.pipeline().names());
        assertEquals(true, embeddedChannel.pipeline().names().contains(ChannelHandlerNames.MQTT_CONNECT_HANDLER));
        assertEquals(
                false, embeddedChannel.pipeline().names().contains(ChannelHandlerNames.MQTT_DISALLOW_SECOND_CONNECT));

        final CONNECT connect = new CONNECT.Mqtt3Builder().withProtocolVersion(ProtocolVersion.MQTTv3_1_1)
                .withClientIdentifier("clientId")
                .withCleanStart(true)
                .build();

        embeddedChannel.writeInbound(connect);

        System.out.println(embeddedChannel.pipeline().names());
        assertEquals(false, embeddedChannel.pipeline().names().contains(ChannelHandlerNames.MQTT_CONNECT_HANDLER));
        assertEquals(
                true, embeddedChannel.pipeline().names().contains(ChannelHandlerNames.MQTT_DISALLOW_SECOND_CONNECT));
    }

    @Test
    public void test_client_takeover() {

        final EmbeddedChannel oldChannel = new EmbeddedChannel(new DummyHandler());

        when(channelPersistence.get(eq("sameClientId"))).thenReturn(oldChannel);

        assertTrue(oldChannel.isOpen());
        assertTrue(embeddedChannel.isOpen());

        final CONNECT connect1 = new CONNECT.Mqtt3Builder().withProtocolVersion(ProtocolVersion.MQTTv3_1_1)
                .withClientIdentifier("sameClientId")
                .build();

        embeddedChannel.writeInbound(connect1);

        assertTrue(embeddedChannel.isOpen());
        assertFalse(oldChannel.isOpen());
        assertTrue(oldChannel.attr(ChannelAttributes.TAKEN_OVER).get());
    }

    @Test
    public void test_client_takeover_retry() {

        final SettableFuture<Void> disconnectFuture = SettableFuture.create();
        final EmbeddedChannel oldChannel = new EmbeddedChannel(new DummyHandler());
        oldChannel.attr(ChannelAttributes.TAKEN_OVER).set(true);
        oldChannel.attr(ChannelAttributes.DISCONNECT_FUTURE).set(disconnectFuture);

        when(channelPersistence.get(eq("sameClientId"))).thenReturn(oldChannel);

        assertTrue(oldChannel.isOpen());
        assertTrue(embeddedChannel.isOpen());

        final CONNECT connect1 = new CONNECT.Mqtt3Builder().withProtocolVersion(ProtocolVersion.MQTTv3_1_1)
                .withClientIdentifier("sameClientId")
                .build();

        embeddedChannel.writeInbound(connect1);

        assertTrue(oldChannel.isOpen());
        assertTrue(embeddedChannel.isOpen());

        oldChannel.attr(ChannelAttributes.TAKEN_OVER).set(false);
        disconnectFuture.set(null);

        embeddedChannel.runPendingTasks();

        assertTrue(embeddedChannel.isOpen());
        assertFalse(oldChannel.isOpen());
        assertTrue(oldChannel.attr(ChannelAttributes.TAKEN_OVER).get());
    }

    @Test
    public void test_too_long_clientid() throws Exception {

        configurationService.restrictionsConfiguration().setMaxClientIdLength(5);
        createHandler();

        final CountDownLatch latch = new CountDownLatch(1);

        final CONNECT connect = new CONNECT.Mqtt3Builder().withProtocolVersion(ProtocolVersion.MQTTv3_1_1)
                .withClientIdentifier("123456")
                .build();

        final CountDownLatch eventLatch = new CountDownLatch(1);
        embeddedChannel.pipeline().addLast(new TestDisconnectEventHandler(eventLatch));
        embeddedChannel.closeFuture().addListener((ChannelFutureListener) future -> latch.countDown());

        embeddedChannel.writeInbound(connect);

        assertEquals(true, latch.await(5, TimeUnit.SECONDS));
        assertEquals(true, eventLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void test_will_topic_dollar() throws Exception {

        createHandler();

        final CountDownLatch latch = new CountDownLatch(1);

        final MqttWillPublish willPublish = new MqttWillPublish.Mqtt3Builder().withPayload(new byte[100])
                .withQos(QoS.EXACTLY_ONCE)
                .withHivemqId("hmqid")
                .withTopic("top/#")
                .build();

        final CONNECT connect = new CONNECT.Mqtt3Builder().withProtocolVersion(ProtocolVersion.MQTTv3_1_1)
                .withClientIdentifier("123456")
                .withWillPublish(willPublish)
                .build();

        final CountDownLatch eventLatch = new CountDownLatch(1);
        embeddedChannel.pipeline().addLast(new TestDisconnectEventHandler(eventLatch));
        embeddedChannel.closeFuture().addListener((ChannelFutureListener) future -> latch.countDown());

        embeddedChannel.writeInbound(connect);

        assertEquals(true, latch.await(5, TimeUnit.SECONDS));
        assertEquals(true, eventLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void test_will_exceed_max_qos_mqtt5() throws Exception {

        createHandler();
        configurationService.mqttConfiguration().setMaximumQos(QoS.AT_MOST_ONCE);

        final CountDownLatch latch = new CountDownLatch(1);

        final MqttWillPublish willPublish = new MqttWillPublish.Mqtt5Builder().withPayload("message".getBytes())
                .withQos(QoS.EXACTLY_ONCE)
                .withUserProperties(Mqtt5UserProperties.NO_USER_PROPERTIES)
                .withTopic("topic")
                .build();

        final CONNECT connect =
                new CONNECT.Mqtt5Builder().withClientIdentifier("123456").withWillPublish(willPublish).build();

        final CountDownLatch eventLatch = new CountDownLatch(1);
        embeddedChannel.pipeline().addLast(new TestDisconnectEventHandler(eventLatch));
        embeddedChannel.closeFuture().addListener((ChannelFutureListener) future -> latch.countDown());

        embeddedChannel.writeInbound(connect);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(eventLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void test_too_long_clientid_mqtt5() throws Exception {

        configurationService.restrictionsConfiguration().setMaxClientIdLength(5);
        createHandler();

        final CountDownLatch latch = new CountDownLatch(1);

        final CONNECT connect = new CONNECT.Mqtt5Builder().withClientIdentifier("123456").build();

        final CountDownLatch eventLatch = new CountDownLatch(1);
        embeddedChannel.pipeline().addLast(new TestDisconnectEventHandler(eventLatch));
        embeddedChannel.closeFuture().addListener((ChannelFutureListener) future -> latch.countDown());

        embeddedChannel.writeInbound(connect);

        assertEquals(true, latch.await(5, TimeUnit.SECONDS));
        assertEquals(true, eventLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void test_wrong_event_is_passed_through() throws Exception {

        final CollectUserEventsHandler<String> collectUserEventsHandler = new CollectUserEventsHandler<>(String.class);
        embeddedChannel.pipeline().addLast(collectUserEventsHandler);

        final String test = "test";

        handler.userEventTriggered(ctx, test);

        assertNotNull(collectUserEventsHandler.pollEvent());

    }

    @Test
    public void test_will_retain_not_supported_mqtt3() throws InterruptedException {
        embeddedChannel.attr(ChannelAttributes.MQTT_VERSION).set(ProtocolVersion.MQTTv3_1_1);

        configurationService.mqttConfiguration().setRetainedMessagesEnabled(false);

        createHandler();

        final MqttWillPublish willPublish = new MqttWillPublish.Mqtt3Builder().withPayload(new byte[100])
                .withQos(QoS.EXACTLY_ONCE)
                .withHivemqId("hmqid")
                .withTopic("top")
                .withRetain(true)
                .build();

        final CONNECT connect = new CONNECT.Mqtt3Builder().withProtocolVersion(ProtocolVersion.MQTTv3_1_1)
                .withClientIdentifier("123456")
                .withWillPublish(willPublish)
                .build();

        final CountDownLatch eventLatch = new CountDownLatch(1);
        embeddedChannel.pipeline().addLast(new TestDisconnectEventHandler(eventLatch));

        embeddedChannel.writeInbound(connect);

        final CONNACK connack = embeddedChannel.readOutbound();
        assertNotNull(connack);
        assertEquals(Mqtt3ConnAckReturnCode.REFUSED_NOT_AUTHORIZED, connack.getReturnCode());
        assertFalse(embeddedChannel.isActive());
        assertEquals(true, eventLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void test_will_retain_supported_mqtt3() {
        embeddedChannel.attr(ChannelAttributes.MQTT_VERSION).set(ProtocolVersion.MQTTv3_1_1);

        configurationService.mqttConfiguration().setRetainedMessagesEnabled(true);

        createHandler();

        final MqttWillPublish willPublish = new MqttWillPublish.Mqtt3Builder().withPayload(new byte[100])
                .withQos(QoS.EXACTLY_ONCE)
                .withHivemqId("hmqid")
                .withTopic("top")
                .withRetain(true)
                .build();

        final CONNECT connect = new CONNECT.Mqtt3Builder().withProtocolVersion(ProtocolVersion.MQTTv3_1_1)
                .withClientIdentifier("123456")
                .withWillPublish(willPublish)
                .build();

        embeddedChannel.writeInbound(connect);
        embeddedChannel.runPendingTasks();

        final CONNACK connack = embeddedChannel.readOutbound();
        assertNotNull(connack);
        assertEquals(Mqtt3ConnAckReturnCode.ACCEPTED, connack.getReturnCode());
        assertTrue(embeddedChannel.isActive());
        assertTrue(embeddedChannel.attr(ChannelAttributes.AUTH_PERMISSIONS).get() != null);
    }

    @Test
    public void test_will_retain_not_supported_mqtt5() throws InterruptedException {
        embeddedChannel.attr(ChannelAttributes.MQTT_VERSION).set(ProtocolVersion.MQTTv5);

        configurationService.mqttConfiguration().setRetainedMessagesEnabled(false);

        createHandler();

        final MqttWillPublish willPublish = new MqttWillPublish.Mqtt3Builder().withPayload(new byte[100])
                .withQos(QoS.EXACTLY_ONCE)
                .withHivemqId("hmqid")
                .withTopic("top")
                .withRetain(true)
                .build();

        final CONNECT connect =
                new CONNECT.Mqtt5Builder().withClientIdentifier("123456").withWillPublish(willPublish).build();

        final CountDownLatch eventLatch = new CountDownLatch(1);
        embeddedChannel.pipeline().addLast(new TestDisconnectEventHandler(eventLatch));
        embeddedChannel.writeInbound(connect);

        final CONNACK connack = embeddedChannel.readOutbound();
        assertNotNull(connack);
        assertEquals(Mqtt5ConnAckReasonCode.RETAIN_NOT_SUPPORTED, connack.getReasonCode());
        assertFalse(embeddedChannel.isActive());
        assertEquals(true, eventLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void test_will_retain_supported_mqtt5() {
        embeddedChannel.attr(ChannelAttributes.MQTT_VERSION).set(ProtocolVersion.MQTTv5);

        configurationService.mqttConfiguration().setRetainedMessagesEnabled(true);

        createHandler();

        final MqttWillPublish willPublish = new MqttWillPublish.Mqtt5Builder().withPayload(new byte[100])
                .withQos(QoS.EXACTLY_ONCE)
                .withHivemqId("hmqid")
                .withTopic("top")
                .withRetain(true)
                .build();

        final CONNECT connect =
                new CONNECT.Mqtt5Builder().withClientIdentifier("123456").withWillPublish(willPublish).build();

        embeddedChannel.writeInbound(connect);

        final CONNACK connack = embeddedChannel.readOutbound();
        assertNotNull(connack);
        assertEquals(Mqtt5ConnAckReasonCode.SUCCESS, connack.getReasonCode());
        assertTrue(embeddedChannel.isActive());
        assertTrue(embeddedChannel.attr(ChannelAttributes.AUTH_PERMISSIONS).get() != null);
    }

    /* ******
     * Auth *
     ********/

    @Test(timeout = 5000)
    public void test_auth_in_progress_message_handler_is_set() {
        when(authenticators.getAuthenticatorProviderMap()).thenReturn(
                ImmutableMap.of("extension1", new WrappedAuthenticatorProvider((input) -> (SimpleAuthenticator) (simpleAuthInput, simpleAuthOutput) -> {
                }, isolatedPluginClassloader)));
        when(authenticators.areAuthenticatorsAvailable()).thenReturn(true);

        createHandler();
        final CONNECT connect =
                new CONNECT.Mqtt5Builder().withClientIdentifier("client").withAuthMethod("someMethod").build();


        embeddedChannel.writeInbound(connect);

        assertNotNull(embeddedChannel.pipeline().get(ChannelHandlerNames.AUTH_IN_PROGRESS_MESSAGE_HANDLER));
    }

    @Test(timeout = 5000)
    public void test_auth_in_progress_message_handler_is_removed() {
        createHandler();
        embeddedChannel.attr(ChannelAttributes.AUTH_METHOD).set("someMethod");
        embeddedChannel.pipeline()
                .addAfter(ChannelHandlerNames.MQTT_MESSAGE_DECODER,
                        ChannelHandlerNames.AUTH_IN_PROGRESS_MESSAGE_HANDLER,
                        channelDependencies.getAuthInProgressMessageHandler());
        final CONNECT connect =
                new CONNECT.Mqtt5Builder().withClientIdentifier("client").withAuthMethod("someMethod").build();

        handler.connectSuccessfulAuthenticated(ctx, connect, null);

        assertNull(embeddedChannel.pipeline().get(ChannelHandlerNames.AUTH_IN_PROGRESS_MESSAGE_HANDLER));
        assertTrue(embeddedChannel.attr(ChannelAttributes.AUTH_AUTHENTICATED).get());
    }

    @Test(timeout = 5000)
    public void test_auth_is_performed() {
        when(authenticators.getAuthenticatorProviderMap()).thenReturn(
                ImmutableMap.of(
                        "extension1", new WrappedAuthenticatorProvider((input) -> (SimpleAuthenticator) (simpleAuthInput, simpleAuthOutput) -> {
                        }, isolatedPluginClassloader),
                        "extension2", new WrappedAuthenticatorProvider((input) -> (SimpleAuthenticator) (simpleAuthInput, simpleAuthOutput) -> {
                        }, isolatedPluginClassloader2),
                        "extension3", new WrappedAuthenticatorProvider((input) -> (SimpleAuthenticator) (simpleAuthInput, simpleAuthOutput) -> {
                        }, isolatedPluginClassloader3)));
        createHandler();
        when(authenticators.areAuthenticatorsAvailable()).thenReturn(true);

        final CONNECT connect =
                new CONNECT.Mqtt5Builder().withClientIdentifier("client").withAuthMethod("someMethod").build();
        embeddedChannel.writeInbound(connect);

        verify(pluginTaskExecutorService, times(3)).handlePluginInOutTaskExecution(any(), any(), any(), any());
    }

    @Test(timeout = 5000)
    public void test_connack_success_if_no_authenticator_registered() {
        createHandler();

        final CONNECT connect =
                new CONNECT.Mqtt5Builder().withClientIdentifier("client").withAuthMethod("someMethod").build();
        embeddedChannel.writeInbound(connect);

        final CONNACK connack = embeddedChannel.readOutbound();

        assertNotNull(connack);
        assertEquals(Mqtt5ConnAckReasonCode.SUCCESS, connack.getReasonCode());
        assertTrue(embeddedChannel.isActive());
    }

    @Test(timeout = 5000)
    public void test_connect_unauthenticated_if_no_authenticator_registered_and_internal_config_allow() {

        createHandler();

        when(authenticators.areAuthenticatorsAvailable()).thenReturn(true);

        final CONNECT connect =
                new CONNECT.Mqtt5Builder().withClientIdentifier("client").withAuthMethod("someMethod").build();
        embeddedChannel.writeInbound(connect);

        final CONNACK connack = embeddedChannel.readOutbound();

        assertNotNull(connack);
        assertEquals(Mqtt5ConnAckReasonCode.SUCCESS, connack.getReasonCode());
        assertTrue(embeddedChannel.isActive());
    }

    @Test(timeout = 5000)
    public void test_connect_successfully_if_no_authenticator_present_and_no_auth_info_given() {
        createHandler();
        final CONNECT connect = new CONNECT.Mqtt5Builder().withClientIdentifier("client").build();
        embeddedChannel.writeInbound(connect);

        final CONNACK connack = embeddedChannel.readOutbound();

        assertNotNull(connack);
        assertEquals(Mqtt5ConnAckReasonCode.SUCCESS, connack.getReasonCode());
        assertTrue(embeddedChannel.isActive());
        assertFalse(embeddedChannel.attr(ChannelAttributes.AUTH_AUTHENTICATED).get());
    }

    @Test(timeout = 5000)
    public void test_will_authorization_success() {
        createHandler();

        final MqttWillPublish willPublish = new MqttWillPublish.Mqtt5Builder().withTopic("topic")
                .withQos(QoS.AT_LEAST_ONCE).withPayload(new byte[]{1, 2, 3}).build();

        final CONNECT connect =
                new CONNECT.Mqtt5Builder().withClientIdentifier("client")
                        .withWillPublish(willPublish).build();

        final ModifiableDefaultPermissionsImpl permissions = new ModifiableDefaultPermissionsImpl();
        permissions.add(new TopicPermissionBuilderImpl(new TestConfigurationBootstrap().getFullConfigurationService()).topicFilter("topic").type(TopicPermission.PermissionType.ALLOW).build());

        embeddedChannel.attr(ChannelAttributes.AUTH_PERMISSIONS).set(permissions);

        embeddedChannel.writeInbound(connect);

        final CONNACK connack = embeddedChannel.readOutbound();

        assertNotNull(connack);
        assertEquals(Mqtt5ConnAckReasonCode.SUCCESS, connack.getReasonCode());
        assertTrue(embeddedChannel.isActive());
    }

    @Test(timeout = 5000)
    public void test_will_authorizer_success() {
        createHandler();

        final MqttWillPublish willPublish = new MqttWillPublish.Mqtt5Builder().withTopic("topic")
                .withQos(QoS.AT_LEAST_ONCE).withPayload(new byte[]{1, 2, 3}).build();

        final CONNECT connect =
                new CONNECT.Mqtt5Builder().withClientIdentifier("client")
                        .withWillPublish(willPublish).build();


        final PublishAuthorizerResult result = new PublishAuthorizerResult(AckReasonCode.SUCCESS, null, true);
        embeddedChannel.pipeline().fireUserEventTriggered(new AuthorizeWillResultEvent(connect, result));

        embeddedChannel.runPendingTasks();
        final CONNACK connack = embeddedChannel.readOutbound();

        assertNotNull(connack);
        assertEquals(Mqtt5ConnAckReasonCode.SUCCESS, connack.getReasonCode());
        assertTrue(embeddedChannel.isActive());
    }

    @Test(timeout = 5000)
    public void test_will_authorizer_fail() {
        createHandler();

        final MqttWillPublish willPublish = new MqttWillPublish.Mqtt5Builder().withTopic("topic")
                .withQos(QoS.AT_LEAST_ONCE).withPayload(new byte[]{1, 2, 3}).build();

        final CONNECT connect =
                new CONNECT.Mqtt5Builder().withClientIdentifier("client")
                        .withWillPublish(willPublish).build();


        final PublishAuthorizerResult result = new PublishAuthorizerResult(AckReasonCode.NOT_AUTHORIZED, null, true);
        embeddedChannel.pipeline().fireUserEventTriggered(new AuthorizeWillResultEvent(connect, result));

        embeddedChannel.runPendingTasks();
        final CONNACK connack = embeddedChannel.readOutbound();

        assertNotNull(connack);
        assertEquals(Mqtt5ConnAckReasonCode.NOT_AUTHORIZED, connack.getReasonCode());
        assertFalse(embeddedChannel.isActive());
    }

    @Test(timeout = 5000)
    public void test_will_authorizer_disconnect() {
        createHandler();

        final MqttWillPublish willPublish = new MqttWillPublish.Mqtt5Builder().withTopic("topic")
                .withQos(QoS.AT_LEAST_ONCE).withPayload(new byte[]{1, 2, 3}).build();

        final CONNECT connect =
                new CONNECT.Mqtt5Builder().withClientIdentifier("client")
                        .withWillPublish(willPublish).build();


        final PublishAuthorizerResult result = new PublishAuthorizerResult(AckReasonCode.NOT_AUTHORIZED,
                null, true, DisconnectReasonCode.PAYLOAD_FORMAT_INVALID);
        embeddedChannel.pipeline().fireUserEventTriggered(new AuthorizeWillResultEvent(connect, result));

        embeddedChannel.runPendingTasks();
        final CONNACK connack = embeddedChannel.readOutbound();

        assertNotNull(connack);
        assertEquals(Mqtt5ConnAckReasonCode.PAYLOAD_FORMAT_INVALID, connack.getReasonCode());
        assertFalse(embeddedChannel.isActive());
    }

    @Test(timeout = 5000)
    public void test_will_authorizer_next_no_perms() {
        createHandler();

        final MqttWillPublish willPublish = new MqttWillPublish.Mqtt5Builder().withTopic("topic")
                .withQos(QoS.AT_LEAST_ONCE).withPayload(new byte[]{1, 2, 3}).build();

        final CONNECT connect =
                new CONNECT.Mqtt5Builder().withClientIdentifier("client")
                        .withWillPublish(willPublish).build();


        final PublishAuthorizerResult result = new PublishAuthorizerResult(null, null, true);
        embeddedChannel.pipeline().fireUserEventTriggered(new AuthorizeWillResultEvent(connect, result));

        embeddedChannel.runPendingTasks();
        final CONNACK connack = embeddedChannel.readOutbound();

        assertNotNull(connack);
        assertEquals(Mqtt5ConnAckReasonCode.NOT_AUTHORIZED, connack.getReasonCode());
        assertFalse(embeddedChannel.isActive());
    }

    @Test(timeout = 5000)
    public void test_will_authorizer_next_perms_avail_allow() {
        createHandler();

        final MqttWillPublish willPublish = new MqttWillPublish.Mqtt5Builder().withTopic("topic")
                .withQos(QoS.AT_LEAST_ONCE).withPayload(new byte[]{1, 2, 3}).build();

        final CONNECT connect =
                new CONNECT.Mqtt5Builder().withClientIdentifier("client")
                        .withWillPublish(willPublish).build();

        final ModifiableDefaultPermissionsImpl permissions = new ModifiableDefaultPermissionsImpl();
        permissions.add(new TopicPermissionBuilderImpl(new TestConfigurationBootstrap().getFullConfigurationService()).topicFilter("topic").type(TopicPermission.PermissionType.ALLOW).build());
        embeddedChannel.attr(ChannelAttributes.AUTH_PERMISSIONS).set(permissions);

        final PublishAuthorizerResult result = new PublishAuthorizerResult(null, null, true);
        embeddedChannel.pipeline().fireUserEventTriggered(new AuthorizeWillResultEvent(connect, result));

        embeddedChannel.runPendingTasks();
        final CONNACK connack = embeddedChannel.readOutbound();

        assertNotNull(connack);
        assertEquals(Mqtt5ConnAckReasonCode.SUCCESS, connack.getReasonCode());
        assertTrue(embeddedChannel.isActive());
    }

    @Test(timeout = 5000)
    public void test_will_authorizer_next_perms_avail_default_allow() {
        createHandler();

        final MqttWillPublish willPublish = new MqttWillPublish.Mqtt5Builder().withTopic("topic")
                .withQos(QoS.AT_LEAST_ONCE).withPayload(new byte[]{1, 2, 3}).build();

        final CONNECT connect =
                new CONNECT.Mqtt5Builder().withClientIdentifier("client")
                        .withWillPublish(willPublish).build();

        final ModifiableDefaultPermissionsImpl permissions = new ModifiableDefaultPermissionsImpl();
        permissions.setDefaultBehaviour(DefaultAuthorizationBehaviour.ALLOW);
        embeddedChannel.attr(ChannelAttributes.AUTH_PERMISSIONS).set(permissions);

        final PublishAuthorizerResult result = new PublishAuthorizerResult(null, null, true);
        embeddedChannel.pipeline().fireUserEventTriggered(new AuthorizeWillResultEvent(connect, result));

        embeddedChannel.runPendingTasks();
        final CONNACK connack = embeddedChannel.readOutbound();

        assertNotNull(connack);
        assertEquals(Mqtt5ConnAckReasonCode.SUCCESS, connack.getReasonCode());
        assertTrue(embeddedChannel.isActive());
    }

    @Test(timeout = 5000)
    public void test_will_authorizer_next_perms_avail_deny() {
        createHandler();

        final MqttWillPublish willPublish = new MqttWillPublish.Mqtt5Builder().withTopic("topic")
                .withQos(QoS.AT_LEAST_ONCE).withPayload(new byte[]{1, 2, 3}).build();

        final CONNECT connect =
                new CONNECT.Mqtt5Builder().withClientIdentifier("client")
                        .withWillPublish(willPublish).build();

        final ModifiableDefaultPermissionsImpl permissions = new ModifiableDefaultPermissionsImpl();
        permissions.add(new TopicPermissionBuilderImpl(new TestConfigurationBootstrap().getFullConfigurationService()).topicFilter("topic").type(TopicPermission.PermissionType.DENY).build());
        embeddedChannel.attr(ChannelAttributes.AUTH_PERMISSIONS).set(permissions);

        final PublishAuthorizerResult result = new PublishAuthorizerResult(null, null, true);
        embeddedChannel.pipeline().fireUserEventTriggered(new AuthorizeWillResultEvent(connect, result));

        embeddedChannel.runPendingTasks();
        final CONNACK connack = embeddedChannel.readOutbound();

        assertNotNull(connack);
        assertEquals(Mqtt5ConnAckReasonCode.NOT_AUTHORIZED, connack.getReasonCode());
        assertFalse(embeddedChannel.isActive());
    }

    @Test(timeout = 5000)
    public void test_will_authorizer_next_perms_avail_default_deny() {
        createHandler();

        final MqttWillPublish willPublish = new MqttWillPublish.Mqtt5Builder().withTopic("topic")
                .withQos(QoS.AT_LEAST_ONCE).withPayload(new byte[]{1, 2, 3}).build();

        final CONNECT connect =
                new CONNECT.Mqtt5Builder().withClientIdentifier("client")
                        .withWillPublish(willPublish).build();

        final ModifiableDefaultPermissionsImpl permissions = new ModifiableDefaultPermissionsImpl();
        permissions.setDefaultBehaviour(DefaultAuthorizationBehaviour.DENY);
        embeddedChannel.attr(ChannelAttributes.AUTH_PERMISSIONS).set(permissions);

        final PublishAuthorizerResult result = new PublishAuthorizerResult(null, null, true);
        embeddedChannel.pipeline().fireUserEventTriggered(new AuthorizeWillResultEvent(connect, result));

        embeddedChannel.runPendingTasks();
        final CONNACK connack = embeddedChannel.readOutbound();

        assertNotNull(connack);
        assertEquals(Mqtt5ConnAckReasonCode.NOT_AUTHORIZED, connack.getReasonCode());
        assertFalse(embeddedChannel.isActive());
    }

    @Test(timeout = 5000)
    public void test_will_authorization_fail() {
        createHandler();

        final MqttWillPublish willPublish = new MqttWillPublish.Mqtt5Builder().withTopic("topic")
                .withQos(QoS.AT_LEAST_ONCE).withPayload(new byte[]{1, 2, 3}).build();

        final CONNECT connect =
                new CONNECT.Mqtt5Builder().withClientIdentifier("client")
                        .withWillPublish(willPublish).build();

        final ModifiableDefaultPermissionsImpl permissions = new ModifiableDefaultPermissionsImpl();
        permissions.add(new TopicPermissionBuilderImpl(new TestConfigurationBootstrap().getFullConfigurationService()).topicFilter("topic").type(TopicPermission.PermissionType.DENY).build());

        embeddedChannel.attr(ChannelAttributes.AUTH_PERMISSIONS).set(permissions);

        embeddedChannel.writeInbound(connect);

        final CONNACK connack = embeddedChannel.readOutbound();

        assertNotNull(connack);
        assertEquals(Mqtt5ConnAckReasonCode.NOT_AUTHORIZED, connack.getReasonCode());
        assertFalse(embeddedChannel.isActive());
    }

    @Test(timeout = 5000)
    public void test_set_client_settings() {
        createHandler();
        embeddedChannel.attr(ChannelAttributes.AUTH_METHOD).set("someMethod");
        embeddedChannel.pipeline()
                .addAfter(ChannelHandlerNames.MQTT_MESSAGE_DECODER,
                        ChannelHandlerNames.AUTH_IN_PROGRESS_MESSAGE_HANDLER,
                        channelDependencies.getAuthInProgressMessageHandler());
        final CONNECT connect =
                new CONNECT.Mqtt5Builder().withClientIdentifier("client").withAuthMethod("someMethod").build();

        final ModifiableClientSettingsImpl clientSettings = new ModifiableClientSettingsImpl(65535);
        clientSettings.setClientReceiveMaximum(123);
        clientSettings.setOverloadProtectionThrottlingLevel(NONE);
        handler.connectSuccessfulAuthenticated(ctx, connect, clientSettings);

        assertTrue(embeddedChannel.attr(ChannelAttributes.AUTH_AUTHENTICATED).get());
        assertEquals(123, embeddedChannel.attr(ChannelAttributes.CLIENT_RECEIVE_MAXIMUM).get().intValue());
        assertEquals(123, connect.getReceiveMaximum());
        assertEquals(NONE, embeddedChannel.attr(ChannelAttributes.OVERLOAD_PROTECTION_THROTTLING_LEVEL).get());
    }

    private void createHandler() {
        if (embeddedChannel.pipeline().names().contains(ChannelHandlerNames.MQTT_CONNECT_HANDLER)) {
            embeddedChannel.pipeline().remove(ChannelHandlerNames.MQTT_CONNECT_HANDLER);
        }
        if (embeddedChannel.pipeline().names().contains(ChannelHandlerNames.MQTT_MESSAGE_ID_RETURN_HANDLER)) {
            embeddedChannel.pipeline().remove(ChannelHandlerNames.MQTT_MESSAGE_ID_RETURN_HANDLER);
        }
        if (embeddedChannel.pipeline().names().contains(ChannelHandlerNames.STOP_READING_AFTER_CONNECT_HANDLER)) {
            embeddedChannel.pipeline().remove(ChannelHandlerNames.STOP_READING_AFTER_CONNECT_HANDLER);
        }

        configurationService.mqttConfiguration().setServerReceiveMaximum(10);

        final Provider<OrderedTopicHandler> orderedTopicHandlerProvider =
                () -> new OrderedTopicHandlerDummy();
        final Provider<FlowControlHandler> flowControlHandlerProvider =
                () -> new FlowControlHandler(configurationService.mqttConfiguration(),
                        new Mqtt5ServerDisconnector(new MqttDisconnectUtil(new EventLog())));

        handler = new ConnectHandler(new DisconnectClientOnConnectMessageHandler(eventLog), clientSessionPersistence,
                channelPersistence, configurationService, eventLog,
                orderedTopicHandlerProvider, flowControlHandlerProvider, mqttConnacker,
                new TopicAliasLimiterImpl(), authenticators,
                pluginTaskExecutorService, channelDependencies, mock(PluginOutPutAsyncer.class),
                Mockito.mock(AuthenticatorProviderInputFactory.class),
                mock(PublishPollService.class), mock(SharedSubscriptionService.class), authorizers, pluginAuthorizerService);

        handler.postConstruct();
        embeddedChannel.pipeline()
                .addAfter(ChannelHandlerNames.MQTT_MESSAGE_DECODER, ChannelHandlerNames.MQTT_CONNECT_HANDLER, handler);
        embeddedChannel.pipeline()
                .addBefore(ChannelHandlerNames.MQTT_CONNECT_HANDLER, ChannelHandlerNames.STOP_READING_AFTER_CONNECT_HANDLER,
                        new DummyHandler());
        embeddedChannel.pipeline()
                .addBefore(ChannelHandlerNames.STOP_READING_AFTER_CONNECT_HANDLER, ChannelHandlerNames.MQTT_MESSAGE_ID_RETURN_HANDLER,
                        new DummyHandler());
    }

    private void buildPipeline() {
        embeddedChannel.pipeline()
                .addFirst(ChannelHandlerNames.MQTT_MESSAGE_DECODER,
                        TestMqttDecoder.create(true));
        embeddedChannel.pipeline().addLast(ChannelHandlerNames.GLOBAL_THROTTLING_HANDLER, new DummyHandler());
        embeddedChannel.attr(ChannelAttributes.CLIENT_ID).set("clientId");
        embeddedChannel.attr(ChannelAttributes.MQTT_VERSION).set(ProtocolVersion.MQTTv5);

        createHandler();

        final ClientSessionSubscriptionPersistence clientSessionSubscriptionPersistence = mock(ClientSessionSubscriptionPersistence.class);
        when(clientSessionSubscriptionPersistence.getSubscriptions(anyString())).thenReturn(ImmutableSet.of(new Topic("t1", QoS.AT_LEAST_ONCE), new Topic("t2", QoS.AT_MOST_ONCE)));

        embeddedChannel.pipeline().addAfter(ChannelHandlerNames.MQTT_CONNECT_HANDLER, ChannelHandlerNames.MQTT_CONNECT_PERSISTENCE_HANDLER,
                new ConnectPersistenceUpdateHandler(clientSessionPersistence, clientSessionSubscriptionPersistence, mock(MessageIDPools.class), channelPersistence, singleWriterService));

        ctx = embeddedChannel.pipeline().context(ConnectHandler.class);

        embeddedChannel.attr(ChannelAttributes.PLUGIN_CONNECT_EVENT_SENT).set(true);
    }

    private static class OrderedTopicHandlerDummy extends OrderedTopicHandler {

    }

    private static class TestDisconnectEventHandler extends SimpleChannelInboundHandler<CONNECT> {
        private final CountDownLatch eventLatch;

        public TestDisconnectEventHandler(final CountDownLatch eventLatch) {
            this.eventLatch = eventLatch;
        }

        @Override
        protected void channelRead0(final ChannelHandlerContext ctx, final CONNECT msg) {
            ctx.fireChannelRead(msg);
        }

        @Override
        public void userEventTriggered(final ChannelHandlerContext ctx, final Object evt) {
            if (evt instanceof OnServerDisconnectEvent) {
                eventLatch.countDown();
            }
        }
    }
}