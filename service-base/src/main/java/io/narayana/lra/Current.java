/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra;

import static io.narayana.lra.LRAConstants.PARENT_LRA_PARAM_NAME;
import static io.narayana.lra.LRAConstants.QUERY_FIELD_SEPARATOR;
import static io.narayana.lra.LRAConstants.QUERY_PAIR_SEPARATOR;
import static org.eclipse.microprofile.lra.annotation.ws.rs.LRA.LRA_HTTP_CONTEXT_HEADER;

import io.narayana.lra.logging.LRALogger;
import io.narayana.lra.spi.LRAContextResolver;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriBuilder;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Stack;
import java.util.concurrent.ConcurrentHashMap;

// similar to ThreadActionData except it need to be available on the client side
// for use by NarayanaLRAClient and ServerLRAFilter
public class Current {
    private static final ThreadLocal<Current> lraContexts = new ThreadLocal<>();

    /**
     * Use this cache to prevent from {@link ThreadLocal} spilling. This cache is incremented when the request filter
     * method is invoked and decremented when the response filter method is invoked. In this sense, if the response
     * filter method runs on different thread (meaning it doesn't clear the right {@link ThreadLocal}) we still remove
     * it from the cache.
     */
    private static final Map<URI, Integer> activeLRACache = new ConcurrentHashMap<>();

    private static final ThreadLocal<String> authToken = new ThreadLocal<>();

    /**
     * The (optional) resolver that reads the current LRA from the request context the JAX-RS runtime propagates, rather
     * than from the {@link ThreadLocal}, which the JAX-RS specification does not guarantee is shared between the filter
     * and the resource method. Discovered once via the {@link ServiceLoader} because the classpath does not change at
     * runtime; {@code null} when no implementation is registered (for example a pure, standalone client with no server
     * request context), in which case {@link #peek()} falls back to the {@link ThreadLocal}.
     */
    private static final LRAContextResolver CONTEXT_RESOLVER = loadContextResolver();

    private static LRAContextResolver loadContextResolver() {
        try {
            Iterator<LRAContextResolver> it = ServiceLoader.load(LRAContextResolver.class).iterator();
            LRAContextResolver resolver = it.hasNext() ? it.next() : null;

            if (resolver == null && LRALogger.logger.isTraceEnabled()) {
                LRALogger.logger.trace("No LRAContextResolver is registered; Current.peek() will rely on the ThreadLocal");
            }

            return resolver;
        } catch (Throwable t) {
            // a badly registered or unloadable provider must not break context handling: behave as if none is present,
            // but log it because it silently disables cross-thread LRA context propagation
            if (LRALogger.logger.isDebugEnabled()) {
                LRALogger.logger.debugf("Failed to load an LRAContextResolver; Current.peek() will rely on the "
                        + "ThreadLocal: %s", t.getMessage());
            }

            return null;
        }
    }

    /**
     * Read the current LRA from the request context propagated by the JAX-RS runtime, via the registered
     * {@link LRAContextResolver}. The {@link org.eclipse.microprofile.lra.annotation.ws.rs.LRA#LRA_HTTP_CONTEXT_HEADER}
     * is written by the {@code ServerLRAFilter} request filter and, unlike a raw {@link ThreadLocal}, travels reliably
     * to whichever thread runs the resource method.
     *
     * @return the current LRA carried in the request context, or {@code null} if no resolver is registered, there is no
     *         active request context, or the request carries no LRA (e.g. pure client/standalone use)
     */
    private static URI inboundHeaderLRA() {
        if (CONTEXT_RESOLVER == null) {
            return null;
        }

        try {
            return CONTEXT_RESOLVER.currentLRA();
        } catch (Throwable t) {
            // no active context or a resolver failure: behave as if there were no LRA and fall back to the ThreadLocal.
            // Trace level because this is hit on every call with no active request context (e.g. pure client use)
            if (LRALogger.logger.isTraceEnabled()) {
                LRALogger.logger.tracef("LRAContextResolver could not resolve the current LRA; falling back to the "
                        + "ThreadLocal: %s", t.getMessage());
            }

            return null;
        }
    }

    public static void setAuthToken(String token) {
        authToken.set(token);
    }

    public static String getAuthToken() {
        return authToken.get();
    }

    public static void clearAuthToken() {
        authToken.remove();
    }

    @SuppressWarnings("ConstantConditions")
    public static void addActiveLRACache(URI lraId) {
        if (lraId == null) {
            return;
        }

        activeLRACache.merge(lraId, 1, Integer::sum);
    }

    public static void removeActiveLRACache(URI lraId) {
        if (lraId == null) {
            return;
        }

        activeLRACache.compute(lraId, (k, v) -> {
            if (v == null) {
                return null; // already absent; nothing to do
            }

            int next = v - 1;
            return next <= 0 ? null : next; // remove at 0
        });
    }

    private final Stack<URI> stack;
    private Map<String, Object> state;

    private Current(URI url) {
        stack = new Stack<>();
        stack.push(url);
    }

    public static Object putState(String key, Object value) {
        Current current = lraContexts.get();

        if (current != null) {
            return current.updateState(key, value);
        }

        return null;
    }

    public static Object getState(String key) {
        Current current = lraContexts.get();

        if (current != null && current.state != null) {
            return current.state.get(key);
        }

        return null;
    }

    private static String getParents(URI uri) {
        String query = uri.getQuery();

        if (query != null) {
            for (String nvpair : query.split(QUERY_PAIR_SEPARATOR)) {
                if (nvpair.startsWith(PARENT_LRA_PARAM_NAME + QUERY_FIELD_SEPARATOR)) {
                    return nvpair.split(QUERY_FIELD_SEPARATOR)[1];
                }
            }
        }

        return null;
    }

    // construct the LRA URI including the parent hierarchy as a query parameter
    public static URI buildFullLRAUrl(String baseURI, URI parentId) throws URISyntaxException {
        // is the parent part of a hierarchy
        String parents = Current.getParents(parentId); // gets the hierarchy form the query param
        // we have the hierarchy so remove the query parameter
        String gParent = new URI(parentId.getScheme(),
                parentId.getAuthority(),
                parentId.getPath(),
                null, // skip the query string
                parentId.getFragment())
                .toASCIIString();

        if (parents != null) {
            gParent += parents + ","; // , separated list of the hierarchy
        }

        return UriBuilder.fromUri(baseURI).queryParam(PARENT_LRA_PARAM_NAME, gParent).build();
    }

    // given a URL extract the immediate parent of
    public static String getFirstParent(URI parent) throws UnsupportedEncodingException {
        String query = parent == null ? null : parent.getQuery();

        if (query != null) {
            for (String param : query.split(QUERY_PAIR_SEPARATOR)) {
                if (param.startsWith(PARENT_LRA_PARAM_NAME + QUERY_FIELD_SEPARATOR)) {
                    String parents = param.split(QUERY_FIELD_SEPARATOR, 2)[1];

                    // parents is a comma separated list of parents (the first one is the direct parent)
                    if (parents != null) {
                        String[] pa = parents.split(",");

                        if (pa.length > 0) {
                            return URLDecoder.decode(pa[0], StandardCharsets.UTF_8);
                        }
                    }

                    break;
                }
            }
        }

        return null;
    }

    public Object updateState(String key, Object value) {
        if (state == null) {
            state = new HashMap<>();
        }

        return state.put(key, value);
    }

    private static void clearContext(Current current) {
        if (current.state != null) {
            current.state.clear();
        }

        lraContexts.set(null);
    }

    public static URI peek() {
        // The LRA the current request resolved, as propagated by RESTEasy in the request context (may be null when
        // there is no server request context, e.g. pure client/standalone use, or when the filter intentionally
        // removed the header for a non-transactional method that still pushed a context onto the ThreadLocal).
        URI header = inboundHeaderLRA();
        Current current = lraContexts.get();
        URI top = current != null && !current.stack.empty() ? current.stack.peek() : null;

        if (header != null) {
            // A server request resolved an LRA and RESTEasy propagated it to this thread. Prefer the ThreadLocal only
            // when it genuinely belongs to this request (it still contains the propagated LRA) so that a programmatic
            // nested push (e.g. NarayanaLRAClient.startLRA) made inside the resource method is honoured. Otherwise the
            // ThreadLocal is stale/foreign/absent (the JAX-RS filter and resource method ran on different threads) and
            // the propagated header is authoritative.
            if (current != null && current.stack.contains(header)) {
                return top;
            }

            return header;
        }

        if (top != null && !activeLRACache.containsKey(top)) {
            // we cleaned the Current on different thread, so we need to clear the context
            // that was set by previous request filter and wasn't cleaned by the response filter
            Current.popAll();
            return null;
        }

        return top;
    }

    public static URI pop() {
        Current current = lraContexts.get();
        URI lraId = null;

        if (current != null) {
            lraId = current.stack.pop(); // there must be at least one

            if (current.stack.empty()) {
                clearContext(current);
            }
        }

        return lraId;
    }

    // dissassociate an LRA from the callers thread (including any child LRAs)
    public static boolean pop(URI lra) {
        Current current = lraContexts.get();

        if (current == null || !current.stack.contains(lra)) {
            return false;
        }

        current.stack.remove(lra);

        // pop children
        // since child LRAs are contingent upon the parent, popping a parent should also pop the children

        // check every LRA associated with the calling thread and if it is a child of lra then pop it
        // the lra that is being popped is a parent of nextLRA:
        current.stack.removeIf(nextLRA -> isParentOf(lra, nextLRA));

        if (current.stack.empty()) {
            clearContext(current);
        }

        return true;
    }

    /*
     * return true if child is nested under parent
     * ie if child contains a query param matching child
     */
    private static boolean isParentOf(URI parent, URI child) {
        String qs = child.getQuery();

        if (qs == null) {
            return false; // child is top level
        }

        String theParent = parent.toASCIIString();
        String[] params = qs.split(QUERY_PAIR_SEPARATOR);

        for (String param : params) {
            String[] nvp = param.split(QUERY_FIELD_SEPARATOR);

            if (nvp.length == 2 && nvp[0].contains(PARENT_LRA_PARAM_NAME)) { // ignore null parameter values
                // Child has a parent. See if its parent matches theParent:
                String parentCandidate = URLDecoder.decode(nvp[1], StandardCharsets.UTF_8);

                if (parentCandidate.contains(theParent) || theParent.contains(parentCandidate)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * push the current context onto the stack of contexts for this thread
     *
     * @param lraId id of context to push (must not be null)
     */
    public static void push(URI lraId) {
        Current current = lraContexts.get();

        if (current == null) {
            lraContexts.set(new Current(lraId));
        } else {
            if (!current.stack.contains(lraId)) {
                current.stack.push(lraId);
            }
        }
    }

    public static List<Object> getContexts() {
        Current current = lraContexts.get();

        if (current == null) {
            return new ArrayList<>();
        }

        return new ArrayList<>(current.stack);
    }

    /**
     * If there is an LRA context for the current request then add it to the provided response headers, otherwise
     * remove the header. The value written is reconciled with {@link #peek()} (see {@link #reconciledContexts(URI)})
     * so that a stale/foreign {@link ThreadLocal} — the case where the filter and the resource method ran on different
     * threads — is never echoed back to the caller.
     *
     * @param responseContext the header map to add the LRA context to
     */
    public static void updateLRAContext(ContainerResponseContext responseContext) {
        URI lraId = Current.peek();

        if (lraId != null) {
            responseContext.getHeaders().put(LRA_HTTP_CONTEXT_HEADER, reconciledContexts(lraId));
        } else {
            responseContext.getHeaders().remove(LRA_HTTP_CONTEXT_HEADER);
        }
    }

    /**
     * The LRA context(s) to echo on the response, reconciled with the LRA that {@link #peek()} resolved for the current
     * request. When the {@link ThreadLocal} genuinely owns that LRA the full stack is returned, preserving the parent
     * hierarchy of a programmatic nested push; otherwise the {@link ThreadLocal} is stale/foreign (the filter and the
     * resource method ran on different threads) and only the resolved LRA is returned — which is self-describing, as an
     * LRA URI already encodes its parent hierarchy — so a leaked context from a previous request is never echoed back.
     *
     * @param current the reconciled current LRA, as returned by {@link #peek()} (must not be {@code null})
     * @return the contexts to write into the {@code LRA_HTTP_CONTEXT_HEADER}
     */
    private static List<Object> reconciledContexts(URI current) {
        Current threadLocal = lraContexts.get();

        if (threadLocal != null && threadLocal.stack.contains(current)) {
            return new ArrayList<>(threadLocal.stack);
        }

        List<Object> contexts = new ArrayList<>();
        contexts.add(current);

        return contexts;
    }

    public static void updateLRAContext(URI lraId, MultivaluedMap<String, String> headers) {
        headers.putSingle(LRA_HTTP_CONTEXT_HEADER, lraId.toString());
        push(lraId);
    }

    public static void popAll() {
        lraContexts.remove();
    }

    public static void clearContext(MultivaluedMap<String, String> headers) {
        headers.remove(LRA_HTTP_CONTEXT_HEADER);
        popAll();
    }

    public static <T> T getLast(List<T> objects) {
        return objects == null ? null : objects.stream().reduce((a, b) -> b).orElse(null);
    }
}
