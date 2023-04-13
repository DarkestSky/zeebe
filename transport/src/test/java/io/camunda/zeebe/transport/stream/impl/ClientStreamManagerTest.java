/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.transport.stream.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.atomix.cluster.MemberId;
import io.atomix.cluster.messaging.ClusterCommunicationService;
import io.camunda.zeebe.scheduler.testing.TestConcurrencyControl;
import io.camunda.zeebe.transport.stream.impl.messages.PushStreamRequest;
import io.camunda.zeebe.util.buffer.BufferUtil;
import io.camunda.zeebe.util.buffer.BufferWriter;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClientStreamManagerTest {

  private final DirectBuffer streamType = BufferUtil.wrapString("foo");
  private final TestMetadata metadata = new TestMetadata(1);
  private final ClientStreamRegistry<TestMetadata> registry = new ClientStreamRegistry<>();
  private final ClusterCommunicationService mockTransport = mock(ClusterCommunicationService.class);
  private final ClientStreamManager<TestMetadata> clientStreamManager =
      new ClientStreamManager<>(
          registry, new ClientStreamRequestManager<>(mockTransport, new TestConcurrencyControl()));

  @BeforeEach
  void setup() {

    when(mockTransport.send(any(), any(), any(), any(), any(), any()))
        .thenReturn(CompletableFuture.completedFuture(null));
  }

  @Test
  void shouldAddStream() {
    // when
    final var uuid = clientStreamManager.add(streamType, metadata, p -> {});

    // then
    assertThat(registry.get(uuid)).isNotNull();
  }

  @Test
  void shouldAggregateStreamsWithSameStreamTypeAndMetadata() {
    // when
    final var uuid1 =
        clientStreamManager.add(BufferUtil.wrapString("foo"), new TestMetadata(1), p -> {});
    final var uuid2 =
        clientStreamManager.add(BufferUtil.wrapString("foo"), new TestMetadata(1), p -> {});
    final var stream1 = registry.getClient(uuid1).orElseThrow();
    final var stream2 = registry.getClient(uuid2).orElseThrow();

    // then
    assertThat(stream1.getServerStream().getStreamId())
        .isEqualTo(stream2.getServerStream().getStreamId());
  }

  @Test
  void shouldNoAggregateStreamsWithDifferentMetadata() {
    // when
    final var uuid1 = clientStreamManager.add(streamType, new TestMetadata(1), p -> {});
    final var uuid2 = clientStreamManager.add(streamType, new TestMetadata(2), p -> {});
    final var stream1 = registry.getClient(uuid1).orElseThrow();
    final var stream2 = registry.getClient(uuid2).orElseThrow();

    // then
    assertThat(stream1.getServerStream().getStreamId())
        .isNotEqualTo(stream2.getServerStream().getStreamId());
  }

  @Test
  void shouldNoAggregateStreamsWithDifferentStreamType() {
    // when
    final var uuid1 = clientStreamManager.add(BufferUtil.wrapString("foo"), metadata, p -> {});
    final var uuid2 = clientStreamManager.add(BufferUtil.wrapString("bar"), metadata, p -> {});
    final var stream1 = registry.getClient(uuid1).orElseThrow();
    final var stream2 = registry.getClient(uuid2).orElseThrow();

    // then
    assertThat(stream1.getServerStream().getStreamId())
        .isNotEqualTo(stream2.getServerStream().getStreamId());
  }

  @Test
  void shouldOpenStreamToExistingServers() {
    // given
    final MemberId server1 = MemberId.from("1");
    clientStreamManager.onServerJoined(server1);
    final MemberId server2 = MemberId.from("2");
    clientStreamManager.onServerJoined(server2);

    // when
    final var uuid = clientStreamManager.add(streamType, metadata, p -> {});

    // then
    final UUID serverStreamId = getServerStreamId(uuid);
    final var stream = registry.get(serverStreamId).orElseThrow();

    assertThat(stream.isConnected(server1)).isTrue();
    assertThat(stream.isConnected(server2)).isTrue();
  }

  @Test
  void shouldOpenStreamToNewlyAddedServer() {
    // given
    final var uuid = clientStreamManager.add(streamType, metadata, p -> {});
    final var serverStream = registry.get(getServerStreamId(uuid)).orElseThrow();

    // when
    final MemberId server = MemberId.from("3");
    clientStreamManager.onServerJoined(server);

    // then
    assertThat(serverStream.isConnected(server)).isTrue();
  }

  @Test
  void shouldOpenStreamToNewlyAddedServerForAllOpenStreams() {
    // given
    final var stream1 = clientStreamManager.add(BufferUtil.wrapString("foo"), metadata, p -> {});
    final var stream2 = clientStreamManager.add(BufferUtil.wrapString("bar"), metadata, p -> {});
    final var serverStream1 = registry.get(getServerStreamId(stream1)).orElseThrow();
    final var serverStream2 = registry.get(getServerStreamId(stream2)).orElseThrow();
    // when
    final MemberId server = MemberId.from("3");
    clientStreamManager.onServerJoined(server);

    // then
    assertThat(serverStream1.isConnected(server)).isTrue();
    assertThat(serverStream2.isConnected(server)).isTrue();
  }

  @Test
  void shouldRemoveStream() {
    // given
    final var uuid = clientStreamManager.add(streamType, metadata, p -> {});
    final var serverStreamId = getServerStreamId(uuid);

    // when
    clientStreamManager.remove(uuid);

    // then
    assertThat(registry.getClient(uuid)).isEmpty();
    assertThat(registry.get(serverStreamId)).isEmpty();
  }

  @Test
  void shouldNotRemoveIfOtherClientStreamExist() {
    // given
    final var uuid1 = clientStreamManager.add(streamType, metadata, p -> {});
    final var uuid2 = clientStreamManager.add(streamType, metadata, p -> {});
    final var serverStreamId = getServerStreamId(uuid1);

    // when
    clientStreamManager.remove(uuid1);

    // then
    assertThat(registry.getClient(uuid1)).isEmpty();
    assertThat(registry.getClient(uuid2)).isPresent();
    assertThat(registry.get(serverStreamId)).isPresent();
  }

  @Test
  void shouldPushPayloadToClient() {
    // given
    final DirectBuffer payloadReceived = new UnsafeBuffer();
    final var clientStreamId = clientStreamManager.add(streamType, metadata, payloadReceived::wrap);
    final var streamId = getServerStreamId(clientStreamId);

    // when
    final var payloadPushed = BufferUtil.wrapString("data");
    final var request = new PushStreamRequest().streamId(streamId).payload(payloadPushed);
    final CompletableFuture<Void> future = new CompletableFuture<>();
    clientStreamManager.onPayloadReceived(request, future);

    // then
    assertThat(future).succeedsWithin(Duration.ofMillis(100));
    assertThat(payloadReceived).isEqualTo(payloadPushed);
  }

  @Test
  void shouldNotPushIfNoStream() {
    // given -- no stream registered

    // when
    final var payloadPushed = BufferUtil.wrapString("data");
    final var request = new PushStreamRequest().streamId(UUID.randomUUID()).payload(payloadPushed);
    final CompletableFuture<Void> future = new CompletableFuture<>();
    clientStreamManager.onPayloadReceived(request, future);

    // then
    assertThat(future)
        .failsWithin(Duration.ofMillis(100))
        .withThrowableOfType(ExecutionException.class)
        .withCauseInstanceOf(NoSuchStreamException.class);
  }

  @Test
  void shouldForwardErrorWhenPushFails() {
    // given
    final var clientStreamId =
        clientStreamManager.add(
            streamType,
            metadata,
            p -> {
              throw new RuntimeException("Expected");
            });
    final var streamId = getServerStreamId(clientStreamId);

    // when
    final var payloadPushed = BufferUtil.wrapString("data");
    final var request = new PushStreamRequest().streamId(streamId).payload(payloadPushed);
    final CompletableFuture<Void> future = new CompletableFuture<>();
    clientStreamManager.onPayloadReceived(request, future);

    // then
    assertThat(future)
        .failsWithin(Duration.ofMillis(100))
        .withThrowableOfType(ExecutionException.class)
        .withCauseInstanceOf(RuntimeException.class)
        .withMessageContaining("Expected");
  }

  @Test
  void shouldRemoveServerFromClientStream() {
    // given
    final MemberId server = MemberId.from("1");
    clientStreamManager.onServerJoined(server);
    final var uuid = clientStreamManager.add(streamType, metadata, p -> {});
    final var stream = registry.get(getServerStreamId(uuid)).orElseThrow();
    assertThat(stream.isConnected(server)).isTrue();

    // when
    clientStreamManager.onServerRemoved(server);

    // then
    assertThat(stream.isConnected(server)).isFalse();
  }

  private UUID getServerStreamId(final UUID clientStreamId) {
    return registry.getClient(clientStreamId).orElseThrow().getServerStream().getStreamId();
  }

  private record TestMetadata(int data) implements BufferWriter {
    @Override
    public int getLength() {
      return Integer.BYTES;
    }

    @Override
    public void write(final MutableDirectBuffer buffer, final int offset) {
      buffer.putInt(offset, data);
    }
  }
}
