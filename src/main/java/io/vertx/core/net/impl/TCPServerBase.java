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
package io.vertx.core.net.impl;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.traffic.GlobalTrafficShapingHandler;
import io.netty.util.concurrent.GenericFutureListener;
import io.vertx.core.Closeable;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.buffer.impl.PartialPooledByteBufAllocator;
import io.vertx.core.impl.AddressResolver;
import io.vertx.core.impl.ContextInternal;
import io.vertx.core.impl.future.PromiseInternal;
import io.vertx.core.impl.VertxInternal;
import io.vertx.core.impl.logging.Logger;
import io.vertx.core.impl.logging.LoggerFactory;
import io.vertx.core.net.*;
import io.vertx.core.spi.metrics.MetricsProvider;
import io.vertx.core.spi.metrics.TCPMetrics;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Base class for TCP servers
 *
 * @author <a href="http://tfox.org">Tim Fox</a>
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
public abstract class TCPServerBase implements Closeable, MetricsProvider {

  private static final Logger log = LoggerFactory.getLogger(NetServerImpl.class);

  protected final Context creatingContext;
  protected final VertxInternal vertx;
  protected final NetServerOptions options;

  // Per server
  private EventLoop eventLoop;
  private Worker childHandler;
  private Handler<Channel> worker;
  private volatile boolean listening;
  private ContextInternal listenContext;
  private TCPServerBase actualServer;

  // Main
  private SSLHelper sslHelper;
  private volatile Future<SslChannelProvider> sslChannelProvider;
  private Future<SslChannelProvider> updateInProgress;
  private GlobalTrafficShapingHandler trafficShapingHandler;
  private ServerChannelLoadBalancer channelBalancer;
  private Future<Channel> bindFuture;
  private Set<TCPServerBase> servers;
  private TCPMetrics<?> metrics;
  private volatile int actualPort;

  public TCPServerBase(VertxInternal vertx, NetServerOptions options) {
    this.vertx = vertx;
    this.options = options.copy();
    this.creatingContext = vertx.getContext();
  }

  public SslContextProvider sslContextProvider() {
    return sslChannelProvider.result().sslContextProvider();
  }

  public int actualPort() {
    TCPServerBase server = actualServer;
    return server != null ? server.actualPort : actualPort;
  }

  public interface Worker {
    void accept(Channel ch, SslChannelProvider sslChannelProvider, SSLHelper sslHelper, ServerSSLOptions sslOptions);
  }

  protected abstract Worker childHandler(ContextInternal context, SocketAddress socketAddress, GlobalTrafficShapingHandler trafficShapingHandler);

  protected GlobalTrafficShapingHandler createTrafficShapingHandler() {
    return createTrafficShapingHandler(vertx.getEventLoopGroup(), options.getTrafficShapingOptions());
  }

  private GlobalTrafficShapingHandler createTrafficShapingHandler(EventLoopGroup eventLoopGroup, TrafficShapingOptions options) {
    if (options == null) {
      return null;
    }
    GlobalTrafficShapingHandler trafficShapingHandler;
    if (options.getMaxDelayToWait() != 0 && options.getCheckIntervalForStats() != 0) {
      long maxDelayToWaitInSeconds = options.getMaxDelayToWaitTimeUnit().toSeconds(options.getMaxDelayToWait());
      long checkIntervalForStatsInSeconds = options.getCheckIntervalForStatsTimeUnit().toSeconds(options.getCheckIntervalForStats());
      trafficShapingHandler = new GlobalTrafficShapingHandler(eventLoopGroup, options.getOutboundGlobalBandwidth(), options.getInboundGlobalBandwidth(), checkIntervalForStatsInSeconds, maxDelayToWaitInSeconds);
    } else if (options.getCheckIntervalForStats() != 0) {
      long checkIntervalForStatsInSeconds = options.getCheckIntervalForStatsTimeUnit().toSeconds(options.getCheckIntervalForStats());
      trafficShapingHandler = new GlobalTrafficShapingHandler(eventLoopGroup, options.getOutboundGlobalBandwidth(), options.getInboundGlobalBandwidth(), checkIntervalForStatsInSeconds);
    } else {
      trafficShapingHandler = new GlobalTrafficShapingHandler(eventLoopGroup, options.getOutboundGlobalBandwidth(), options.getInboundGlobalBandwidth());
    }
    if (options.getPeakOutboundGlobalBandwidth() != 0) {
      trafficShapingHandler.setMaxGlobalWriteSize(options.getPeakOutboundGlobalBandwidth());
    }
    return trafficShapingHandler;
  }

  protected void configure(SSLOptions options) {
  }

  public Future<Void> updateSSLOptions(ServerSSLOptions options) {
    TCPServerBase server = actualServer;
    if (server != null && server != this) {
      return server.updateSSLOptions(options);
    } else {
      ContextInternal ctx = vertx.getOrCreateContext();
      Future<SslChannelProvider> fut;
      synchronized (this) {
        if (updateInProgress == null) {
          ServerSSLOptions sslOptions = (ServerSSLOptions) options.copy();
          configure(sslOptions);
          updateInProgress = sslHelper.resolveSslChannelProvider(sslOptions, null, sslOptions.isSni(), sslOptions.getClientAuth(), sslOptions.getApplicationLayerProtocols(), ctx);
          fut = updateInProgress;
        } else {
          return updateInProgress.mapEmpty().transform(ar -> updateSSLOptions(options));
        }
      }
      fut.onComplete(ar -> {
        synchronized (this) {
          updateInProgress = null;
          if (ar.succeeded()) {
            sslChannelProvider = fut;
          }
        }
      });
      return fut.mapEmpty();
    }
  }

  public Future<TCPServerBase> bind(SocketAddress address) {
    ContextInternal listenContext = vertx.getOrCreateContext();
    return listen(address, listenContext).map(this);
  }

  private synchronized Future<Channel> listen(SocketAddress localAddress, ContextInternal context) {
    if (listening) {
      throw new IllegalStateException("Listen already called");
    }

    this.listenContext = context;
    this.listening = true;
    this.eventLoop = context.nettyEventLoop();

    SocketAddress bindAddress;
    Map<ServerID, TCPServerBase> sharedNetServers = vertx.sharedTCPServers((Class<TCPServerBase>) getClass());
    synchronized (sharedNetServers) {
      actualPort = localAddress.port();
      String hostOrPath = localAddress.isInetSocket() ? localAddress.host() : localAddress.path();
      TCPServerBase main;
      boolean shared;
      ServerID id;
      if (actualPort > 0 || localAddress.isDomainSocket()) {
        id = new ServerID(actualPort, hostOrPath);
        main = sharedNetServers.get(id);
        shared = true;
        bindAddress = localAddress;
      } else {
        if (actualPort < 0) {
          id = new ServerID(actualPort, hostOrPath + "/" + -actualPort);
          main = sharedNetServers.get(id);
          shared = true;
          bindAddress = SocketAddress.inetSocketAddress(0, localAddress.host());
        } else {
          id = new ServerID(actualPort, hostOrPath);
          main = null;
          shared = false;
          bindAddress = localAddress;
        }
      }
      PromiseInternal<Channel> promise = listenContext.promise();
      if (main == null) {

        SSLHelper helper;
        try {
          helper = new SSLHelper(SSLHelper.resolveEngineOptions(options.getSslEngineOptions(), options.isUseAlpn()));
        } catch (Exception e) {
          return context.failedFuture(e);
        }

        // The first server binds the socket
        actualServer = this;
        bindFuture = promise;
        sslHelper = helper;
        trafficShapingHandler = createTrafficShapingHandler();
        childHandler =  childHandler(listenContext, localAddress, trafficShapingHandler);
        worker = ch -> {
          Future<SslChannelProvider> scp = sslChannelProvider;
          childHandler.accept(ch, scp != null ? scp.result() : null, sslHelper, options.getSslOptions());
        };
        servers = new HashSet<>();
        servers.add(this);
        channelBalancer = new ServerChannelLoadBalancer(vertx.getAcceptorEventLoopGroup().next());

        //
        if (options.isSsl() && options.getKeyCertOptions() == null && options.getTrustOptions() == null) {
          return context.failedFuture("Key/certificate is mandatory for SSL");
        }

        // Register the server in the shared server list
        if (shared) {
          sharedNetServers.put(id, this);
        }
        listenContext.addCloseHook(this);

        // Initialize SSL before binding
        if (options.isSsl()) {
          ServerSSLOptions sslOptions = options.getSslOptions();
          configure(sslOptions);
          sslChannelProvider = sslHelper.resolveSslChannelProvider(sslOptions, null, sslOptions.isSni(), sslOptions.getClientAuth(), sslOptions.getApplicationLayerProtocols(), listenContext).onComplete(ar -> {
            if (ar.succeeded()) {
              bind(hostOrPath, context, bindAddress, localAddress, shared, promise, sharedNetServers, id);
            } else {
              promise.fail(ar.cause());
            }
          });
        } else {
          bind(hostOrPath, context, bindAddress, localAddress, shared, promise, sharedNetServers, id);
        }

        bindFuture.onFailure(err -> {
          if (shared) {
            synchronized (sharedNetServers) {
              sharedNetServers.remove(id);
            }
          }
          listening = false;
        });

        return bindFuture;
      } else {
        // Server already exists with that host/port - we will use that
        actualServer = main;
        metrics = main.metrics;
        childHandler =  childHandler(listenContext, localAddress, main.trafficShapingHandler);
        worker = ch -> {
          Future<SslChannelProvider> scp = actualServer.sslChannelProvider;
          childHandler.accept(ch, scp != null ? scp.result() : null, sslHelper, options.getSslOptions());
        };
        actualServer.servers.add(this);
        actualServer.channelBalancer.addWorker(eventLoop, worker);
        listenContext.addCloseHook(this);
        main.bindFuture.onComplete(promise);
        return promise.future();
      }
    }
  }

  private void bind(
    String hostOrPath,
    ContextInternal context,
    SocketAddress bindAddress,
    SocketAddress localAddress,
    boolean shared,
    Promise<Channel> promise,
    Map<ServerID, TCPServerBase> sharedNetServers,
    ServerID id) {
    // Socket bind
    channelBalancer.addWorker(eventLoop, worker);
    ServerBootstrap bootstrap = new ServerBootstrap();
    bootstrap.group(vertx.getAcceptorEventLoopGroup(), channelBalancer.workers());
    if (options.isSsl()) {
      bootstrap.childOption(ChannelOption.ALLOCATOR, PartialPooledByteBufAllocator.INSTANCE);
    } else {
      bootstrap.childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
    }

    bootstrap.childHandler(channelBalancer);
    applyConnectionOptions(localAddress.isDomainSocket(), bootstrap);

    // Actual bind
    io.netty.util.concurrent.Future<Channel> bindFuture = resolveAndBind(context, bindAddress, bootstrap);
    bindFuture.addListener((GenericFutureListener<io.netty.util.concurrent.Future<Channel>>) res -> {
      if (res.isSuccess()) {
        Channel ch = res.getNow();
        log.trace("Net server listening on " + hostOrPath + ":" + ch.localAddress());
        if (shared) {
          ch.closeFuture().addListener((ChannelFutureListener) channelFuture -> {
            synchronized (sharedNetServers) {
              sharedNetServers.remove(id);
            }
          });
        }
        // Update port to actual port when it is not a domain socket as wildcard port 0 might have been used
        if (bindAddress.isInetSocket()) {
          actualPort = ((InetSocketAddress)ch.localAddress()).getPort();
        }
        metrics = createMetrics(localAddress);
        promise.complete(ch);
      } else {
        promise.fail(res.cause());
      }
    });
  }

  public boolean isListening() {
    return listening;
  }

  protected TCPMetrics<?> createMetrics(SocketAddress localAddress) {
    return null;
  }

  /**
   * Apply the connection option to the server.
   *
   * @param domainSocket whether it's a domain socket server
   * @param bootstrap the Netty server bootstrap
   */
  private void applyConnectionOptions(boolean domainSocket, ServerBootstrap bootstrap) {
    vertx.transport().configure(options, domainSocket, bootstrap);
  }


  @Override
  public boolean isMetricsEnabled() {
    return metrics != null;
  }

  @Override
  public synchronized TCPMetrics<?> getMetrics() {
    return actualServer != null ? actualServer.metrics : null;
  }

  @Override
  public synchronized void close(Promise<Void> completion) {
    if (!listening) {
      completion.complete();
      return;
    }
    listening = false;
    listenContext.removeCloseHook(this);
    Map<ServerID, TCPServerBase> servers = vertx.sharedTCPServers((Class<TCPServerBase>) getClass());
    synchronized (servers) {
      ServerChannelLoadBalancer balancer = actualServer.channelBalancer;
      balancer.removeWorker(eventLoop, worker);
      if (balancer.hasHandlers()) {
        // The actual server still has handlers so we don't actually close it
        completion.complete();
      } else {
        actualServer.actualClose(completion);
      }
    }
  }

  private void actualClose(Promise<Void> done) {
    channelBalancer.close();
    bindFuture.onComplete(ar -> {
      if (ar.succeeded()) {
        Channel channel = ar.result();
        ChannelFuture a = channel.close();
        if (metrics != null) {
          a.addListener(cg -> metrics.close());
        }
        a.addListener((PromiseInternal<Void>)done);
      } else {
        done.complete();
      }
    });
  }

  public abstract Future<Void> close();

  public static io.netty.util.concurrent.Future<Channel> resolveAndBind(ContextInternal context,
                                                                        SocketAddress socketAddress,
                                                                        ServerBootstrap bootstrap) {
    VertxInternal vertx = context.owner();
    io.netty.util.concurrent.Promise<Channel> promise = vertx.getAcceptorEventLoopGroup().next().newPromise();
    try {
      bootstrap.channelFactory(vertx.transport().serverChannelFactory(socketAddress.isDomainSocket()));
    } catch (Exception e) {
      promise.setFailure(e);
      return promise;
    }
    if (socketAddress.isDomainSocket()) {
      java.net.SocketAddress converted = vertx.transport().convert(socketAddress);
      ChannelFuture future = bootstrap.bind(converted);
      future.addListener(f -> {
        if (f.isSuccess()) {
          promise.setSuccess(future.channel());
        } else {
          promise.setFailure(f.cause());
        }
      });
    } else {
      SocketAddressImpl impl = (SocketAddressImpl) socketAddress;
      if (impl.ipAddress() != null) {
        bind(bootstrap, impl.ipAddress(), socketAddress.port(), promise);
      } else {
        AddressResolver resolver = vertx.addressResolver();
        io.netty.util.concurrent.Future<InetSocketAddress> fut = resolver.resolveHostname(context.nettyEventLoop(), socketAddress.host());
        fut.addListener((GenericFutureListener<io.netty.util.concurrent.Future<InetSocketAddress>>) future -> {
          if (future.isSuccess()) {
            bind(bootstrap, future.getNow().getAddress(), socketAddress.port(), promise);
          } else {
            promise.setFailure(future.cause());
          }
        });
      }
    }
    return promise;
  }

  private static void bind(ServerBootstrap bootstrap, InetAddress address, int port, io.netty.util.concurrent.Promise<Channel> promise) {
    InetSocketAddress t = new InetSocketAddress(address, port);
    ChannelFuture future = bootstrap.bind(t);
    future.addListener(f -> {
      if (f.isSuccess()) {
        promise.setSuccess(future.channel());
      } else {
        promise.setFailure(f.cause());
      }
    });
  }
}
