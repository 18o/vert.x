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

import io.vertx.core.net.HostAndPort;
import io.vertx.core.net.ProxyOptions;
import io.vertx.core.net.SocketAddress;

import java.util.Objects;

class EndpointKey {

  final boolean ssl;
  final SocketAddress server;
  final HostAndPort authority;
  final ProxyOptions proxyOptions;

  EndpointKey(boolean ssl, ProxyOptions proxyOptions, SocketAddress server, HostAndPort authority) {
    if (server == null) {
      throw new NullPointerException("No null server address");
    }
    if (authority == null) {
      throw new NullPointerException("No null authority address");
    }
    this.ssl = ssl;
    this.proxyOptions = proxyOptions;
    this.authority = authority;
    this.server = server;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    EndpointKey that = (EndpointKey) o;
    return ssl == that.ssl && server.equals(that.server) && authority.equals(that.authority) && equals(proxyOptions, that.proxyOptions);
  }

  @Override
  public int hashCode() {
    int result = ssl ? 1 : 0;
    result = 31 * result + authority.hashCode();
    result = 31 * result + server.hashCode();
    if (proxyOptions != null) {
      result = 31 * result + hashCode(proxyOptions);
    }
    return result;
  }

  private static boolean equals(ProxyOptions options1, ProxyOptions options2) {
    if (options1 == options2) {
      return true;
    }
    if (options1 != null && options2 != null) {
      return Objects.equals(options1.getHost(), options2.getHost()) &&
        options1.getPort() == options2.getPort() &&
        Objects.equals(options1.getUsername(), options2.getUsername()) &&
        Objects.equals(options1.getPassword(), options2.getPassword());
    }
    return false;
  }

  private static int hashCode(ProxyOptions options) {
    if (options.getUsername() != null && options.getPassword() != null) {
      return Objects.hash(options.getHost(), options.getPort(), options.getType(), options.getUsername(), options.getPassword());
    } else {
      return Objects.hash(options.getHost(), options.getPort(), options.getType());
    }
  }

  @Override
  public String toString() {
    return server.toString();
  }
}
