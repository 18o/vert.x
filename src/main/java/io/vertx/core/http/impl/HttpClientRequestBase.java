/*
 * Copyright (c) 2011-2019 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
 * which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */

package io.vertx.core.http.impl;

import io.netty.handler.codec.http2.Http2Error;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.http.*;
import io.vertx.core.impl.ContextInternal;
import io.vertx.core.impl.future.PromiseInternal;
import io.vertx.core.net.HostAndPort;

import java.util.Objects;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
public abstract class HttpClientRequestBase implements HttpClientRequest {

  protected final HttpClientImpl client;
  protected final ContextInternal context;
  protected final HttpClientStream stream;
  protected final boolean ssl;
  private io.vertx.core.http.HttpMethod method;
  private HostAndPort authority;
  private String uri;
  private String path;
  private String query;
  private final PromiseInternal<HttpClientResponse> responsePromise;
  private Handler<HttpClientRequest> pushHandler;
  private long currentTimeoutTimerId = -1;
  private long currentTimeoutMs;
  private long lastDataReceived;
  private Throwable timeoutFired;

  HttpClientRequestBase(HttpClientImpl client, HttpClientStream stream, PromiseInternal<HttpClientResponse> responsePromise, boolean ssl, HttpMethod method, HostAndPort authority, String uri) {
    this.client = client;
    this.stream = stream;
    this.responsePromise = responsePromise;
    this.context = responsePromise.context();
    this.uri = uri;
    this.method = method;
    this.authority = authority;
    this.ssl = ssl;

    //
    stream.pushHandler(this::handlePush);
    stream.headHandler(resp -> {
      HttpClientResponseImpl response = new HttpClientResponseImpl(this, stream.version(), stream, resp.statusCode, resp.statusMessage, resp.headers);
      stream.chunkHandler(response::handleChunk);
      stream.endHandler(response::handleEnd);
      stream.priorityHandler(response::handlePriorityChange);
      stream.unknownFrameHandler(response::handleUnknownFrame);
      handleResponse(response);
    });
  }

  protected String authority() {
    if ((authority.port() == 80 && !ssl) || (authority.port() == 443 && ssl) || authority.port() < 0) {
      return authority.host();
    } else {
      return authority.host() + ':' + authority.port();
    }
  }

  @Override
  public int streamId() {
    return stream.id();
  }

  @Override
  public String absoluteURI() {
    return (ssl ? "https://" : "http://") + authority() + uri;
  }

  public String query() {
    if (query == null) {
      query = HttpUtils.parseQuery(uri);
    }
    return query;
  }

  public String path() {
    if (path == null) {
      path = HttpUtils.parsePath(uri);
    }
    return path;
  }

  public synchronized String getURI() {
    return uri;
  }

  @Override
  public synchronized HttpClientRequest setURI(String uri) {
    Objects.requireNonNull(uri);
    this.uri = uri;
    // invalidate cached values
    this.path = null;
    this.query = null;
    return this;
  }

  @Override
  public synchronized HttpClientRequest authority(HostAndPort authority) {
    Objects.requireNonNull(authority);
    this.authority = authority;
    return this;
  }

  @Override
  public synchronized HttpMethod getMethod() {
    return method;
  }

  @Override
  public synchronized HttpClientRequest setMethod(HttpMethod method) {
    Objects.requireNonNull(uri);
    this.method = method;
    return this;
  }

  @Override
  public synchronized HttpClientRequest setTimeout(long timeoutMs) {
    cancelTimeout();
    currentTimeoutMs = timeoutMs;
    currentTimeoutTimerId = context.setTimer(timeoutMs, id -> handleTimeout(timeoutMs));
    return this;
  }

  protected Throwable mapException(Throwable t) {
    if (t instanceof HttpClosedException && timeoutFired != null) {
      t = timeoutFired;
    }
    return t;
  }

  void handleException(Throwable t) {
    fail(t);
  }

  void fail(Throwable t) {
    cancelTimeout();
    responsePromise.tryFail(t);
    HttpClientResponseImpl response = (HttpClientResponseImpl) responsePromise.future().result();
    if (response != null) {
      response.handleException(t);
    }
  }

  void handlePush(HttpClientPush push) {
    HttpClientRequestPushPromise pushReq = new HttpClientRequestPushPromise(push.stream, client, ssl, push.method, push.uri, push.authority, push.headers);
    if (pushHandler != null) {
      pushHandler.handle(pushReq);
    } else {
      pushReq.reset(Http2Error.CANCEL.code());
    }
  }

  void handleResponse(HttpClientResponse resp) {
    handleResponse(responsePromise, resp, cancelTimeout());
  }

  abstract void handleResponse(Promise<HttpClientResponse> promise, HttpClientResponse resp, long timeoutMs);

  private synchronized long cancelTimeout() {
    long ret;
    if ((ret = currentTimeoutTimerId) != -1) {
      client.vertx().cancelTimer(currentTimeoutTimerId);
      currentTimeoutTimerId = -1;
      ret = currentTimeoutMs;
      currentTimeoutMs = 0;
    }
    return ret;
  }

  private void handleTimeout(long timeoutMs) {
    NoStackTraceTimeoutException cause;
    synchronized (this) {
      currentTimeoutTimerId = -1;
      currentTimeoutMs = 0;
      if (lastDataReceived > 0) {
        long now = System.currentTimeMillis();
        long timeSinceLastData = now - lastDataReceived;
        if (timeSinceLastData < timeoutMs) {
          // reschedule
          lastDataReceived = 0;
          setTimeout(timeoutMs - timeSinceLastData);
          return;
        }
      }
      cause = timeoutEx(timeoutMs, method, authority, uri);
      timeoutFired = cause;
    }
    reset(cause);
  }

  static NoStackTraceTimeoutException timeoutEx(long timeoutMs, HttpMethod method, HostAndPort peer, String uri) {
    return new NoStackTraceTimeoutException("The timeout period of " + timeoutMs + "ms has been exceeded while executing " + method + " " + uri + " for server " + peer);
  }

  synchronized void dataReceived() {
    if (currentTimeoutTimerId != -1) {
      lastDataReceived = System.currentTimeMillis();
    }
  }

  @Override
  public boolean reset(long code) {
    return reset(new StreamResetException(code));
  }

  @Override
  public boolean reset(long code, Throwable cause) {
    return reset(new StreamResetException(code, cause));
  }

  abstract boolean reset(Throwable cause);

  @Override
  public Future<HttpClientResponse> response() {
    return responsePromise.future();
  }

  synchronized Handler<HttpClientRequest> pushHandler() {
    return pushHandler;
  }

  @Override
  public synchronized HttpClientRequest pushHandler(Handler<HttpClientRequest> handler) {
    pushHandler = handler;
    return this;
  }
}
