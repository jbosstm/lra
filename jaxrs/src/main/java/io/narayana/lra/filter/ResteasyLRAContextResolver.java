/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.filter;

import static org.eclipse.microprofile.lra.annotation.ws.rs.LRA.LRA_HTTP_CONTEXT_HEADER;

import io.narayana.lra.Current;
import io.narayana.lra.spi.LRAContextResolver;
import jakarta.ws.rs.core.HttpHeaders;
import java.net.URI;
import org.jboss.resteasy.core.ResteasyContext;

/**
 * RESTEasy-backed {@link LRAContextResolver}. RESTEasy propagates its context data map — including the request
 * {@link HttpHeaders} — to whichever thread runs the resource method (and to its contextual executors), so the LRA that
 * {@code ServerLRAFilter} wrote into the {@code LRA_HTTP_CONTEXT_HEADER} can be read reliably here even when the filter
 * and the resource method run on different threads.
 *
 * <p>
 * Registered for the {@link java.util.ServiceLoader} through
 * {@code META-INF/services/io.narayana.lra.spi.LRAContextResolver}. It is only on the classpath where RESTEasy is (the
 * participant and coordinator deployments); a standalone client that does not deploy this module finds no resolver and
 * {@link Current} falls back to its {@code ThreadLocal}.
 */
public class ResteasyLRAContextResolver implements LRAContextResolver {

    @Override
    public URI currentLRA() {
        HttpHeaders headers = ResteasyContext.getContextData(HttpHeaders.class);

        if (headers == null) {
            return null;
        }

        // use getRequestHeader + getLast (not getHeaderString, which comma-joins multiple values) to match the value
        // ServerLRAFilter resolves from the incoming LRA_HTTP_CONTEXT_HEADER
        String last = Current.getLast(headers.getRequestHeader(LRA_HTTP_CONTEXT_HEADER));

        return last == null ? null : URI.create(last);
    }
}
