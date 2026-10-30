/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.spi;

import java.net.URI;

/**
 * Resolves the LRA associated with the request currently being processed on the calling thread, independently of the
 * {@link ThreadLocal} that {@link io.narayana.lra.Current} maintains.
 *
 * <p>
 * The MicroProfile LRA specification does not require that a JAX-RS request filter and the target resource method run
 * on the same thread. When they do not, the {@code ThreadLocal} populated by the inbound filter is either invisible to
 * the resource method or, worse, a pooled thread carries a previous request's LRA into an unrelated request. A resolver
 * gives {@link io.narayana.lra.Current#peek()} a way to read the LRA that the JAX-RS runtime has propagated for the
 * current request (the {@code LRA_HTTP_CONTEXT_HEADER}), which travels reliably to whichever thread runs the resource
 * method.
 *
 * <p>
 * This interface deliberately carries no dependency on any particular JAX-RS implementation. An implementation is
 * discovered lazily through the {@link java.util.ServiceLoader}; runtimes that can propagate the request context (for
 * example RESTEasy) register one via {@code META-INF/services}. When no implementation is present — such as a pure,
 * standalone client with no server request context — {@link io.narayana.lra.Current} falls back to its
 * {@code ThreadLocal}, which is correct there because no cross-thread dispatch takes place.
 */
public interface LRAContextResolver {

    /**
     * @return the LRA propagated for the request currently being processed on the calling thread, or {@code null} when
     *         there is no active request context or the request carries no LRA
     */
    URI currentLRA();
}
