package io.fabric8.kubernetes.client.okhttp;

import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OkHttpWebSocketImplTest {

  private static final ExecutorService EXECUTOR_SERVICE = Executors.newFixedThreadPool(10);

  @Test
  void test_race_condition() {
    OkHttpClient mockOkHttp = mock(OkHttpClient.class);
    OkHttpWebSocketImpl.BuilderImpl builder = new OkHttpWebSocketImpl.BuilderImpl(mockOkHttp);

    when(mockOkHttp.newWebSocket(any(), any())).thenAnswer(invocationOnMock -> {
      WebSocket mock = mock(WebSocket.class);
      WebSocketListener listener = invocationOnMock.getArgument(1, WebSocketListener.class);
      EXECUTOR_SERVICE.submit(() -> {
        try {
          builder.enteredCriticalSectionLatch.await();
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
        Response response = mock(Response.class);
        listener.onOpen(mock, response);
        builder.resumeCriticalSectionLatch.countDown();
      });
      EXECUTOR_SERVICE.submit(() -> listener.onFailure(mock, new RuntimeException("error"), null));
      return mock;
    });

    AtomicBoolean didFail = new AtomicBoolean(false);
    CompletableFuture<io.fabric8.kubernetes.client.http.WebSocket> future = builder
      .uri(URI.create("https://foobar"))
      .buildAsync(new io.fabric8.kubernetes.client.http.WebSocket.Listener() {
        @Override
        public void onError(io.fabric8.kubernetes.client.http.WebSocket webSocket, Throwable error) {
          didFail.set(true);
        }
      });

    try {
      future.get();
    } catch (Exception e) {
      // expected
    }

    // we expect the future to either fail exceptionally or the listener to have failed
    assertThat(future.isCompletedExceptionally() || didFail.get()).withFailMessage("Future completed exceptionally: %b, didFail: %b", future.isCompletedExceptionally(), didFail.get())
      .isTrue();
  }
}
