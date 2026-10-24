package org.castrelyx.manager.hunt.v2;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

class HuntStorageTest {
  @Test
  void cancelsAnOversizedBodyBeforeBufferingItInMemory() {
    var subscriber = HuntStorage.boundedBody(16).apply(null);
    var subscription = mock(Flow.Subscription.class);
    subscriber.onSubscribe(subscription);
    subscriber.onNext(List.of(ByteBuffer.wrap(new byte[8])));
    subscriber.onNext(List.of(ByteBuffer.wrap(new byte[9])));
    verify(subscription).cancel();
    assertThatThrownBy(() -> subscriber.getBody().toCompletableFuture().join())
        .hasCauseInstanceOf(java.io.IOException.class);
  }

  @Test
  void acceptsACompleteBoundedBody() {
    var subscriber = HuntStorage.boundedBody(16).apply(null);
    subscriber.onSubscribe(mock(Flow.Subscription.class));
    subscriber.onNext(List.of(ByteBuffer.wrap(new byte[] {1, 2})));
    subscriber.onComplete();
    assertThat(subscriber.getBody().toCompletableFuture().join()).containsExactly(1, 2);
  }
}
