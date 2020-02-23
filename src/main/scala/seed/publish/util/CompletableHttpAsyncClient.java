package seed.publish.util;

import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.nio.client.HttpAsyncClient;
import org.apache.http.nio.protocol.HttpAsyncRequestProducer;
import org.apache.http.nio.protocol.HttpAsyncResponseConsumer;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

// From https://gist.github.com/tomfitzhenry/4bb032f6a9f56d95f6fb54454e617fc1
public class CompletableHttpAsyncClient {
    private final HttpAsyncClient httpAsyncClient;

    public CompletableHttpAsyncClient(HttpAsyncClient httpAsyncClient) {
        this.httpAsyncClient = httpAsyncClient;
    }

    public <T> CompletableFuture<T> execute(
        HttpAsyncRequestProducer httpAsyncRequestProducer,
        HttpAsyncResponseConsumer<T> httpAsyncResponseConsumer,
        HttpClientContext httpClientContext
    ) {
        return toCompletableFuture(fc ->
            httpAsyncClient.execute(
                httpAsyncRequestProducer,
                httpAsyncResponseConsumer,
                httpClientContext,
                fc));
    }

    private static <T> CompletableFuture<T> toCompletableFuture(
        Consumer<FutureCallback<T>> c
    ) {
        CompletableFuture<T> promise = new CompletableFuture<>();
        c.accept(new FutureCallback<T>() {
            @Override
            public void completed(T t) {
                promise.complete(t);
            }

            @Override
            public void failed(Exception e) {
                promise.completeExceptionally(e);
            }

            @Override
            public void cancelled() {
                promise.cancel(true);
            }
        });

        return promise;
    }
}
