/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.proxy.test.api;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;

/**
 * Activates JAX-RS at the root of the deployment. The application is deployed at the root context
 * (ROOT.war) and with an empty application path so that the proxy callback resource
 * ({@link io.narayana.lra.client.internal.proxy.ParticipantProxyResource}) is reachable at the same URL
 * that {@link io.narayana.lra.client.internal.proxy.ProxyService} advertises to the coordinator, i.e.
 * {@code http://<lra.http.host>:<lra.http.port>/lraproxy/{lra}/{pid}}.
 */
@ApplicationPath("/")
public class JaxRsActivator extends Application {
}
