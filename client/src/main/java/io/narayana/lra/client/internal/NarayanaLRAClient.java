/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.client.internal;

import static io.narayana.lra.LRAConstants.AFTER;
import static io.narayana.lra.LRAConstants.CLIENT_ID_PARAM_NAME;
import static io.narayana.lra.LRAConstants.COMPENSATE;
import static io.narayana.lra.LRAConstants.COMPLETE;
import static io.narayana.lra.LRAConstants.COORDINATOR_PATH_NAME;
import static io.narayana.lra.LRAConstants.FORGET;
import static io.narayana.lra.LRAConstants.LEAVE;
import static io.narayana.lra.LRAConstants.NARAYANA_LRA_API_VERSION_HEADER_NAME;
import static io.narayana.lra.LRAConstants.NARAYANA_LRA_PARTICIPANT_DATA_HEADER_NAME;
import static io.narayana.lra.LRAConstants.NARAYANA_LRA_PARTICIPANT_LINK_HEADER_NAME;
import static io.narayana.lra.LRAConstants.PARENT_LRA_PARAM_NAME;
import static io.narayana.lra.LRAConstants.RECOVERY_COORDINATOR_PATH_NAME;
import static io.narayana.lra.LRAConstants.STATUS;
import static io.narayana.lra.LRAConstants.TIMELIMIT_PARAM_NAME;
import static jakarta.ws.rs.core.Response.Status.BAD_REQUEST;
import static jakarta.ws.rs.core.Response.Status.GONE;
import static jakarta.ws.rs.core.Response.Status.INTERNAL_SERVER_ERROR;
import static jakarta.ws.rs.core.Response.Status.NOT_ACCEPTABLE;
import static jakarta.ws.rs.core.Response.Status.NOT_FOUND;
import static jakarta.ws.rs.core.Response.Status.NO_CONTENT;
import static jakarta.ws.rs.core.Response.Status.OK;
import static jakarta.ws.rs.core.Response.Status.PRECONDITION_FAILED;
import static jakarta.ws.rs.core.Response.Status.SERVICE_UNAVAILABLE;
import static org.eclipse.microprofile.lra.annotation.ws.rs.LRA.LRA_HTTP_RECOVERY_HEADER;

import io.narayana.lra.Current;
import io.narayana.lra.LRAConstants;
import io.narayana.lra.LRAData;
import io.narayana.lra.logging.LRALogger;
import io.smallrye.stork.Stork;
import io.smallrye.stork.api.Service;
import io.smallrye.stork.api.ServiceDefinition;
import io.smallrye.stork.api.config.ConfigWithType;
import io.smallrye.stork.servicediscovery.staticlist.StaticConfiguration;
import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.container.Suspended;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import java.io.Closeable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.lra.annotation.AfterLRA;
import org.eclipse.microprofile.lra.annotation.Compensate;
import org.eclipse.microprofile.lra.annotation.Complete;
import org.eclipse.microprofile.lra.annotation.Forget;
import org.eclipse.microprofile.lra.annotation.LRAStatus;
import org.eclipse.microprofile.lra.annotation.Status;
import org.eclipse.microprofile.lra.annotation.ws.rs.Leave;

/**
 * WARNING: NarayanaLRAClient is an internal utility class and is subject to change
 * (however it will be made available with improved guarantees in a subsequent release).
 * <p>
 * A utility class for controlling the lifecycle of Long Running Actions (LRAs) but the preferred mechanism is to use
 * the annotation in the {@link org.eclipse.microprofile.lra.annotation} package
 */
@RequestScoped
public class NarayanaLRAClient implements Closeable {
    /**
     * The config property key for configuring the URL of a Narayana LRA coordinator
     */
    public static final String LRA_COORDINATOR_URL_KEY = "lra.coordinator.url";
    /**
     * The config property key for configuring the load balancing algorithm for a cluster of coordinators
     */
    public static final String COORDINATOR_LB_METHOD_KEY = "lra.coordinator.lb-method";

    // Load balancing algorithms.
    // The values must match what Stork uses (remark Stork does not define any constants)
    public static final String LB_METHOD_ROUND_ROBIN = "round-robin";
    public static final String LB_METHOD_STICKY = "sticky";
    public static final String LB_METHOD_RANDOM = "random";
    public static final String LB_METHOD_LEAST_REQUESTS = "least-requests";
    public static final String LB_METHOD_LEAST_RESPONSE_TIME = "least-response-time";
    public static final String LB_METHOD_POWER_OF_TWO_CHOICES = "power-of-two-choices";
    public static final String[] NARAYANA_LRA_SUPPORTED_LB_METHODS = new String[] {
            LB_METHOD_ROUND_ROBIN,
            LB_METHOD_STICKY,
            LB_METHOD_RANDOM,
            LB_METHOD_LEAST_REQUESTS,
            LB_METHOD_LEAST_RESPONSE_TIME,
            LB_METHOD_POWER_OF_TWO_CHOICES
    };

    // LRA Coordinator API
    private static final String START_PATH = "/start";
    private static final String LEAVE_PATH = "%s/remove";
    private static final String STATUS_PATH = "%s/status";
    private static final String CLOSE_PATH = "%s/close";
    private static final String CANCEL_PATH = "%s/cancel";

    private static final String LINK_TEXT = "Link";

    /**
     * constrain how long client operations take before giving up
     * <p>
     * WARNING: NarayanaLRAClient is an internal utility class and is subject to change
     * (but it will be made available with improved guarantees in a subsequent release).
     */
    private static final long CLIENT_TIMEOUT = Long.getLong("lra.internal.client.timeout", 10);
    private static final long START_TIMEOUT = Long.getLong("lra.internal.client.timeout.start", CLIENT_TIMEOUT);
    private static final long JOIN_TIMEOUT = Long.getLong("lra.internal.client.timeout.join", CLIENT_TIMEOUT);
    private static final long END_TIMEOUT = Long.getLong("lra.internal.client.end.timeout", CLIENT_TIMEOUT);
    private static final long LEAVE_TIMEOUT = Long.getLong("lra.internal.client.leave.timeout", CLIENT_TIMEOUT);
    private static final long QUERY_TIMEOUT = Long.getLong("lra.internal.client.query.timeout", CLIENT_TIMEOUT);

    private static final Config CONFIG = ConfigProvider.getConfig();

    private Service coordinatorService;
    private URI coordinatorUrl; // default coordinator (when load balancing is enabled a cluster coordinators is used)
    private long coordinatorCount;
    private String lbMethod;
    private boolean lbMethodValid;
    private boolean supportsFailover;
    private boolean storkInitialised;

    /**
     * Creating LRA client. The URL of the LRA coordinator will be taken
     * from system property {@link NarayanaLRAClient#LRA_COORDINATOR_URL_KEY}.
     * If not defined as default value is taken {@code http://localhost:8080/lra-coordinator}.
     * The LRA recovery coordinator will be searched at the sub-path {@value LRAConstants#RECOVERY_COORDINATOR_PATH_NAME}.
     *
     * @throws IllegalStateException thrown when the URL taken from the system property value is not a URL format
     */
    public NarayanaLRAClient() {
        clusterConfig(null);
    }

    /**
     * Creating LRA client where expecting LRA coordinator being available through
     * protocol <i>protocol</i> at <i>host</i>:<i>port</i>/<i>coordinatorPath</i>.
     *
     * @param protocol protocol used to contact the LRA coordinator
     * @param host hostname where the LRA coordinator will be contacted
     * @param port port where the LRA coordinator will be contacted
     * @param coordinatorPath path where the LRA coordinator will be contacted
     */
    @Deprecated(since = "1.0.3.Final", forRemoval = true)
    public NarayanaLRAClient(String protocol, String host, int port, String coordinatorPath) {
        clusterConfig(UriBuilder.fromPath(coordinatorPath).scheme(protocol).host(host).port(port).build());
    }

    /**
     * Creating LRA client where expecting LRA coordinator being available
     * at the provided uri.
     * The LRA recovery coordinator will be searched at the sub-path {@value LRAConstants#RECOVERY_COORDINATOR_PATH_NAME}.
     *
     * @param coordinatorUrl uri of the LRA coordinator
     */
    public NarayanaLRAClient(URI coordinatorUrl) {
        clusterConfig(coordinatorUrl);
    }

    /**
     * Creating LRA client where expecting LRA coordinator being available
     * at the provided URL defined by String.
     * The LRA recovery coordinator will be searched at the sub-path {@value LRAConstants#RECOVERY_COORDINATOR_PATH_NAME}.
     *
     * @param coordinatorUrl url of the LRA coordinator
     * @throws IllegalStateException thrown when the provided URL String is not a URL format
     */
    public NarayanaLRAClient(String coordinatorUrl) {
        clusterConfig(toURI(coordinatorUrl));
    }

    private URI toURI(String coordinatorUrl) {
        try {
            return new URI(coordinatorUrl);
        } catch (URISyntaxException use) {
            String errMsg = LRALogger.i18nLogger.warn_invalid_uri(
                    coordinatorUrl, use.getMessage() + " - NarayanaLRAClient constructor");
            throw new RuntimeException(errMsg, use); // TODO or throw use and change the caller
        }
    }

    private static String getConfigProperty(String key, String defaultValue) {
        try {
            String value = CONFIG.getValue(key, String.class);
            if (value != null && !value.isEmpty()) {
                return value;
            }
        } catch (Exception ignore) {
        }

        return defaultValue;
    }

    private ConfigWithType loadBalancer(String loadBalancer, Map<String, String> loadBalancerParams) {
        return loadBalancer == null ? null : new ConfigWithType() {
            @Override
            public String type() {
                return loadBalancer;
            }

            @Override
            public Map<String, String> parameters() {
                return Objects.requireNonNullElse(loadBalancerParams, Collections.emptyMap());
            }
        };
    }

    private void clusterConfig(URI coordinatorUrl) {
        if (coordinatorUrl != null) {
            this.coordinatorUrl = coordinatorUrl;
            this.coordinatorCount = 1;
        } else {
            // check LRA_COORDINATOR_URL_KEY or use http://localhost:8080/lra-coordinator as the default
            String coordinators = getConfigProperty(LRA_COORDINATOR_URL_KEY, "http://localhost:8080/" + COORDINATOR_PATH_NAME);

            this.coordinatorUrl = toURI(coordinators.split(",")[0]);
            this.coordinatorCount = coordinators.chars().filter(ch -> ch == ',').count() + 1;

            // if there is only one coordinator, we don't need to initialize stork
            if (this.coordinatorCount == 1) {
                return;
            }

            try {
                this.lbMethod = getConfigProperty(COORDINATOR_LB_METHOD_KEY, LB_METHOD_ROUND_ROBIN);
                this.lbMethodValid = Arrays.asList(NARAYANA_LRA_SUPPORTED_LB_METHODS).contains(lbMethod);
                // validate whether the requested load balancer is supported
                if (!lbMethodValid) {
                    // load balancing only applies when starting new LRAs, so log a warning rather than an error
                    LRALogger.i18nLogger.warn_unsupportedLoadBalancer(lbMethod, this.coordinatorUrl.toASCIIString());
                } else {
                    if (LRALogger.logger.isDebugEnabled()) {
                        LRALogger.logger.debugf("using stork with coordinator(s) %s and lb-method %d",
                                coordinators, lbMethod);
                    }

                    ConfigWithType balancer = loadBalancer(lbMethod, null);

                    Stork.initialize();
                    // Note that the NarayanaLRAClient.close method calls Stork.shutdown().
                    // If the caller forgets to call close then subsequent attempts to initialise Stork will be skipped
                    // and any config changes, such as a change of load balancer algorithm, will not take effect
                    var stork = Stork.getInstance()
                            .defineIfAbsent(COORDINATOR_PATH_NAME, ServiceDefinition.of(new StaticConfiguration()
                                    .withAddressList(coordinators), balancer));

                    this.coordinatorService = stork.getService(COORDINATOR_PATH_NAME);
                    this.supportsFailover = Objects.equals(LB_METHOD_ROUND_ROBIN, lbMethod);
                    this.storkInitialised = true;
                }
            } catch (NoClassDefFoundError | IllegalArgumentException error) {
                // missing Stork dependencies on the classpath or invalid load balancing algorithm,
                // so fallback to using a coordinator without load balancing support and pick the first coordinator
                this.coordinatorUrl = coordinatorUrl;
                this.supportsFailover = false;
                LRALogger.i18nLogger.warn_noLoadBalancer(coordinators, error);
            }
        }
    }

    public boolean isLoadBalancing() {
        return coordinatorCount > 1 && lbMethodValid;
    }

    /**
     * Method changes the client's notion about the place where the LRA coordinator can be contacted.
     * It takes the provided URI and tries to derive the URL path of the coordinator responsible
     * for the LRA id. The URL is then used as endpoint for later use of this LRA Narayana client instance.
     *
     * @param lraId LRA id consisting of the coordinator URL and the LRA uid
     */
    public void setCurrentLRA(URI lraId) {
        try {
            this.coordinatorUrl = LRAConstants.getLRACoordinatorUrl(lraId);
        } catch (IllegalStateException e) {
            String logMsg = LRALogger.i18nLogger.error_invalidLraIdFormatToConvertToCoordinatorUrl(lraId.toASCIIString(), e);
            LRALogger.logger.error(logMsg);
            throwGenericLRAException(lraId, BAD_REQUEST.getStatusCode(), logMsg, null);
        }
    }

    public List<LRAData> getAllLRAs() {
        Client client = null;
        try {
            client = getClient();
            Response response = client.target(coordinatorUrl)
                    .request()
                    .header(NARAYANA_LRA_API_VERSION_HEADER_NAME, LRAConstants.CURRENT_API_VERSION_STRING)
                    .async()
                    .get()
                    .get(QUERY_TIMEOUT, TimeUnit.SECONDS);

            if (response.getStatus() != OK.getStatusCode()) {
                LRALogger.logger.debugf("Error getting all LRAs from the coordinator, response status: %d",
                        response.getStatus());
                throw new WebApplicationException(response);
            }

            return response.readEntity(new GenericType<List<LRAData>>() {
            });
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new WebApplicationException(Response.status(SERVICE_UNAVAILABLE)
                    .entity("getAllLRAs client request timed out, try again later").build());
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }

    /**
     * Starting LRA. You provide client id determining the LRA being started.
     *
     * @param clientID client id determining the LRA
     * @return LRA id as URL
     * @throws WebApplicationException thrown when start of the LRA failed
     */
    public URI startLRA(String clientID) throws WebApplicationException {
        return startLRA(clientID, 0L);
    }

    /**
     * Starting LRA. You provide client id that joins the LRA context
     * and is passed when working with the LRA.
     *
     * @param clientID client id determining the LRA
     * @param timeout timeout value in seconds, when timeout-ed the LRA will be compensated
     * @return LRA id as URL
     * @throws WebApplicationException thrown when start of the LRA failed
     */
    private URI startLRA(String clientID, Long timeout) throws WebApplicationException {
        return startLRA(clientID, timeout, ChronoUnit.SECONDS);
    }

    /**
     * Starting LRA. You provide client id that joins the LRA context
     * and is passed when working with the LRA.
     *
     * @param clientID client id determining the LRA
     * @param timeout timeout value, when timeout-ed the LRA will be compensated
     * @param unit timeout unit, when null seconds are used
     * @return LRA id as URL
     * @throws WebApplicationException thrown when start of the LRA failed
     */
    private URI startLRA(String clientID, Long timeout, ChronoUnit unit) throws WebApplicationException {
        return startLRA(getCurrent(), clientID, timeout, unit);
    }

    public URI startLRA(URI parentLRA, String clientID, Long timeout, ChronoUnit unit) throws WebApplicationException {
        return startLRA(parentLRA, clientID, timeout, unit, true);
    }

    /**
     * Starting LRA. You provide client id that joins the LRA context
     * and is passed when working with the LRA.
     *
     * @param parentLRA when the newly started LRA should be nested with this LRA parent, when null the newly started LRA is
     *        top-level
     * @param clientID client id determining the LRA
     * @param timeout timeout value, when timeout-ed the LRA will be compensated
     * @param unit timeout unit, when null seconds are used
     * @return LRA id as URL
     * @throws WebApplicationException thrown when start of the LRA failed
     */
    public URI startLRA(URI parentLRA, String clientID, Long timeout, ChronoUnit unit, boolean verbose)
            throws WebApplicationException {
        if (coordinatorCount > 1 && !lbMethodValid) {
            throw new WebApplicationException(Response.status(SERVICE_UNAVAILABLE)
                    .entity(LRALogger.i18nLogger.error_unsupportedLoadBalancer(lbMethod)).build());
        }

        if (clientID == null) {
            clientID = "";
        }

        if (timeout == null) {
            timeout = 0L;
        } else if (timeout < 0) {
            throwGenericLRAException(parentLRA, BAD_REQUEST.getStatusCode(),
                    LRALogger.i18nLogger.warn_invalid_timeout(timeout), null);
            return null;
        }
        if (unit == null) {
            unit = ChronoUnit.SECONDS;
        }
        try (Client client = getClient()) {
            URI coordinatorInstance; // URI of one of a cluster of coordinators
            String encodedParentLRA = parentLRA == null ? ""
                    : URLEncoder.encode(parentLRA.toString(), StandardCharsets.UTF_8);

            for (int i = 0; i < coordinatorCount; i++) {
                if (coordinatorService != null) {
                    var instance = coordinatorService.selectInstance()
                            .await().atMost(Duration.ofSeconds(START_TIMEOUT));

                    if (LRALogger.logger.isDebugEnabled()) {
                        LRALogger.logger.debugf("Selected coordinator %s:%d%n",
                                instance.getHost(), instance.getPort());
                    }
                    coordinatorInstance = UriBuilder.fromPath(coordinatorUrl.getPath())
                            .scheme(instance.isSecure() ? "https" : "http") // remark do we want to support the "storks" scheme
                            .host(instance.getHost())
                            .port(instance.getPort()).build();
                } else {
                    coordinatorInstance = coordinatorUrl;
                }

                try {
                    Response response = client.target(coordinatorInstance)
                            .path(START_PATH)
                            .queryParam(CLIENT_ID_PARAM_NAME, clientID)
                            .queryParam(TIMELIMIT_PARAM_NAME, Duration.of(timeout, unit).toMillis())
                            .queryParam(PARENT_LRA_PARAM_NAME, encodedParentLRA)
                            .request()
                            .header(NARAYANA_LRA_API_VERSION_HEADER_NAME, LRAConstants.CURRENT_API_VERSION_STRING)
                            .async()
                            .post(null)
                            .get(START_TIMEOUT, TimeUnit.SECONDS);

                    // validate the HTTP status code says an LRA resource was created
                    if (isUnexpectedResponseStatus(response, Response.Status.CREATED)) {
                        if (verbose) {
                            // remark we don't read the entity here since that would close it but the client needs to read it
                            LRALogger.logger.error(
                                    LRALogger.i18nLogger.error_lraCreationUnexpectedStatus(response.getStatus(), ""));
                        }
                        // let the client know the reason for the failure (it's in the entity body of the response object)
                        throw new WebApplicationException(response);
                    }

                    URI lra = URI.create(response.getHeaderString(HttpHeaders.LOCATION));
                    lraTrace(lra, "startLRA returned");

                    Current.push(lra);
                    Current.addActiveLRACache(lra);

                    return lra;
                } catch (InterruptedException | ExecutionException | TimeoutException e) {
                    if (supportsFailover && i == coordinatorCount - 1) {
                        String errMsg = LRALogger.i18nLogger.warn_startLRAFailed(e.getMessage());
                        LRALogger.logger.warn(errMsg, e);
                        /*
                         * bail out since we've either tried all the coordinators or failover isn't supported
                         * with this particular load balancer
                         */
                        throw new WebApplicationException(Response.status(SERVICE_UNAVAILABLE).entity(errMsg).build());
                    }
                    /*
                     * Remark stork does not support dynamically removing failed instances
                     * ie coordinatorService.getInstances()
                     * .await().atMost(Duration.ofSeconds(START_TIMEOUT)).remove(instance); won't work.
                     * Therefore, failover will not work with strategies such as sticky but will work with others
                     * such as round-robin and the caller should call the NarayanaLRAClient constructor again to
                     * obtain a client load balancing and failover properties with the desired properties.
                     */
                }
            }

            throw new WebApplicationException(Response.status(SERVICE_UNAVAILABLE)
                    .entity("no available coordinator").build());
        }
    }

    public void cancelLRA(URI lraId) throws WebApplicationException {
        endLRA(lraId, false, null, null);
    }

    public void closeLRA(URI lraId) throws WebApplicationException {
        endLRA(lraId, true, null, null);
    }

    public void cancelLRA(URI lraId, String compensator, String userData) throws WebApplicationException {
        endLRA(lraId, false, compensator, userData);
    }

    public void closeLRA(URI lraId, String compensator, String userData) throws WebApplicationException {
        endLRA(lraId, true, compensator, userData);
    }

    /**
     * Joining the LRA with identity of `lraId` as participant defined by URIs for complete, compensate, forget, leave,
     * after and status.
     *
     * @param lraId the URI of the LRA to join
     * @param timeLimit how long the participant is prepared to wait for LRA completion
     * @param compensateUri URI for compensation notifications
     * @param completeUri URI for completion notifications
     * @param forgetUri URI for forget callback
     * @param leaveUri URI for leave requests
     * @param statusUri URI for reporting the status of the participant
     * @param compensatorData data provided during compensation
     * @return a recovery URL for this enlistment
     * @throws WebApplicationException if the LRA coordinator failed to enlist the participant
     */
    public URI joinLRA(URI lraId, Long timeLimit,
            URI compensateUri, URI completeUri, URI forgetUri, URI leaveUri, URI afterUri, URI statusUri,
            String compensatorData) throws WebApplicationException {
        return enlistCompensator(lraId, timeLimit, "",
                compensateUri, completeUri,
                forgetUri, leaveUri, afterUri, statusUri,
                null);
    }

    public URI joinLRA(URI lraId, Long timeLimit,
            URI compensateUri, URI completeUri, URI forgetUri, URI leaveUri, URI afterUri, URI statusUri,
            StringBuilder compensatorData) throws WebApplicationException {
        return enlistCompensator(lraId, timeLimit, "",
                compensateUri, completeUri,
                forgetUri, leaveUri, afterUri, statusUri,
                compensatorData);
    }

    /**
     * Joining the LRA with identity of `lraId` as participant defined by a participant URI.
     *
     * @param lraId the URI of the LRA to join
     * @param timeLimit how long the participant is prepared to wait for LRA completion
     * @param participantUri URI of participant for enlistment
     * @param compensatorData data provided during compensation
     * @return a recovery URL for this enlistment
     * @throws WebApplicationException if the LRA coordinator failed to enlist the participant
     */
    public URI joinLRA(URI lraId, Long timeLimit,
            URI participantUri, StringBuilder compensatorData) throws WebApplicationException {
        validateURI(participantUri, false, "Invalid participant URL: %s");
        StringBuilder linkHeaderValue = makeLink(new StringBuilder(), null, "participant", participantUri.toASCIIString());

        return enlistCompensator(lraId, timeLimit, linkHeaderValue.toString(), compensatorData);
    }

    public void leaveLRA(URI lraId, String body) throws WebApplicationException {
        Client client = null;
        Response response;

        try {
            client = getClient();

            response = client.target(String.format(LEAVE_PATH, lraId))
                    .request()
                    .header(NARAYANA_LRA_API_VERSION_HEADER_NAME, LRAConstants.CURRENT_API_VERSION_STRING)
                    .async()
                    .put(body == null ? Entity.text("") : Entity.text(body))
                    .get(LEAVE_TIMEOUT, TimeUnit.SECONDS);

            if (OK.getStatusCode() != response.getStatus()) {
                String logMsg = LRALogger.i18nLogger.error_lraLeaveUnexpectedStatus(lraId, response.getStatus(),
                        response.hasEntity() ? response.readEntity(String.class) : "");
                LRALogger.logger.error(logMsg);
                throwGenericLRAException(null, response.getStatus(), logMsg, null);
            }
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new WebApplicationException(Response
                    .status(SERVICE_UNAVAILABLE)
                    .entity("leave LRA client request timed out, try again later").build());
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }

    /**
     * For particular compensator class it returns termination uris based on the provided base uri.
     * You get map of string and URI.
     *
     * @param compensatorClass compensator class to examine.
     * @param uriPrefix the uri that triggered this join request.
     * @param timeout how long the participant is prepared to wait for LRA
     *        to compensate or complete.
     * @return map of URI
     */
    public static Map<String, String> getTerminationUris(Class<?> compensatorClass, String uriPrefix, Long timeout) {
        Map<String, String> paths = new HashMap<>();
        final boolean[] asyncTermination = { false };

        String timeoutValue = timeout != null ? Long.toString(timeout) : "0";

        Arrays.stream(compensatorClass.getMethods()).forEach(method -> {
            Path pathAnnotation = method.getAnnotation(Path.class);

            if (pathAnnotation != null) {

                if (checkMethod(paths, method, COMPENSATE, pathAnnotation,
                        method.getAnnotation(Compensate.class), uriPrefix) != 0) {
                    paths.put(TIMELIMIT_PARAM_NAME, timeoutValue);

                    if (isAsyncCompletion(method)) {
                        asyncTermination[0] = true;
                    }
                }

                if (checkMethod(paths, method, COMPLETE, pathAnnotation,
                        method.getAnnotation(Complete.class), uriPrefix) != 0) {
                    paths.put(TIMELIMIT_PARAM_NAME, timeoutValue);

                    if (isAsyncCompletion(method)) {
                        asyncTermination[0] = true;
                    }
                }
                checkMethod(paths, method, STATUS, pathAnnotation,
                        method.getAnnotation(Status.class), uriPrefix);
                checkMethod(paths, method, FORGET, pathAnnotation,
                        method.getAnnotation(Forget.class), uriPrefix);

                checkMethod(paths, method, LEAVE, pathAnnotation, method.getAnnotation(Leave.class), uriPrefix);
                checkMethod(paths, method, AFTER, pathAnnotation, method.getAnnotation(AfterLRA.class), uriPrefix);
            }
        });

        if (asyncTermination[0] && !paths.containsKey(STATUS) && !paths.containsKey(FORGET)) {
            String logMsg = LRALogger.i18nLogger.error_asyncTerminationBeanMissStatusAndForget(compensatorClass);
            LRALogger.logger.warn(logMsg);
            throw new WebApplicationException(
                    Response.status(BAD_REQUEST)
                            .entity(logMsg)
                            .build());
        }

        StringBuilder linkHeaderValue = new StringBuilder();

        if (!paths.isEmpty()) {
            paths.forEach((k, v) -> makeLink(linkHeaderValue, null, k, v));
            paths.put(LINK_TEXT, linkHeaderValue.toString());
        }

        return paths;
    }

    /**
     * Providing information if method is defined to be completed asynchronously.
     * This means that {@link Suspended} annotation is available amongst the method parameters
     * while the method is annotated with {@link Complete} or {@link Compensate}.
     *
     * @param method method to be checked for async completion
     * @return true if method is to complete asynchronously, false if synchronously
     */
    public static boolean isAsyncCompletion(Method method) {
        if (method.isAnnotationPresent(Complete.class) || method.isAnnotationPresent(Compensate.class)) {
            for (Annotation[] ann : method.getParameterAnnotations()) {
                for (Annotation an : ann) {
                    if (Suspended.class.isAssignableFrom(an.annotationType())) {
                        LRALogger.logger.warn("JAX-RS @Suspended annotation is untested");
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private static int checkMethod(Map<String, String> paths,
            Method method, String rel,
            Path pathAnnotation,
            Annotation annotationClass,
            String uriPrefix) {
        /*
         * If the annotationClass is null the requested participant annotation is not present,
         * but we also need to check for conformance with the interoperability spec,
         * ie look for paths of the form:
         * `<participant URL>/compensate`
         * `<participant URL>/complete`
         * etc
         */
        if (annotationClass == null) {
            // TODO support standard compenators with: && !pathAnnotation.value().endsWith(rel)) {
            // ie ones that do not use the @Compensate annotation
            return 0;
        }

        // search for a matching JAX-RS method
        for (Annotation annotation : method.getDeclaredAnnotations()) {
            String name = annotation.annotationType().getName();

            if (name.equals(GET.class.getName()) ||
                    name.equals(PUT.class.getName()) ||
                    name.equals(POST.class.getName()) ||
                    name.equals(DELETE.class.getName())) {
                String pathValue = pathAnnotation.value();
                pathValue = pathValue.startsWith("/") ? pathValue : "/" + pathValue;
                String url = String.format("%s%s?%s=%s", uriPrefix, pathValue, LRAConstants.HTTP_METHOD_NAME, name);

                paths.put(rel, url);
                break;
            }
        }

        return 1;
    }

    public LRAStatus getStatus(URI uri) throws WebApplicationException {
        Client client = null;
        Response response;
        URL lraId;

        try {
            // remove the query parameter since it's not in the spec
            lraId = UriBuilder.fromUri(uri).replaceQuery(null).build().toURL();
        } catch (MalformedURLException mue) {
            throwGenericLRAException(null,
                    Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(),
                    "Could not convert LRA to a URL : " + mue.getClass().getName() + ":" + mue.getMessage(), mue);
            return null;
        }

        try {
            client = getClient();
            response = client.target(String.format(STATUS_PATH, lraId))
                    .request()
                    .header(NARAYANA_LRA_API_VERSION_HEADER_NAME, LRAConstants.CURRENT_API_VERSION_STRING)
                    .async()
                    .get()
                    .get(QUERY_TIMEOUT, TimeUnit.SECONDS);

            // TODO add tests for each of these checks
            if (response.getStatus() == NOT_FOUND.getStatusCode()) {
                throw new NotFoundException(response);
            }

            if (response.getStatus() == NO_CONTENT.getStatusCode()) {
                return LRAStatus.Active;
            }

            if (response.getStatus() != OK.getStatusCode()) {
                String logMsg = LRALogger.i18nLogger.error_invalidStatusCode(coordinatorUrl, response.getStatus(), lraId);
                LRALogger.logger.error(logMsg);
                throw new WebApplicationException(response);
            }

            if (!response.hasEntity()) {
                // can't happen since the coordinator returned OK
                String logMsg = LRALogger.i18nLogger.error_noContentOnGetStatus(coordinatorUrl, lraId);
                LRALogger.logger.error(logMsg);
                throw new WebApplicationException(
                        Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(logMsg).build());
            }

            // convert the returned String into a status
            try {
                return LRAStatus.valueOf(response.readEntity(String.class));
            } catch (IllegalArgumentException e) {
                String logMsg = LRALogger.i18nLogger.error_invalidArgumentOnStatusFromCoordinator(coordinatorUrl,
                        lraId, e);
                LRALogger.logger.error(logMsg);
                throw new WebApplicationException(Response.status(INTERNAL_SERVER_ERROR).entity(logMsg).build());
            }
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new WebApplicationException(Response.status(SERVICE_UNAVAILABLE)
                    .entity("get LRA status client request timed out, try again later").build()); // TODO use an i18n logger
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }

    private static StringBuilder makeLink(StringBuilder b, String uriPrefix, String key, String value) {

        if (value == null) {
            return b;
        }

        String terminationUri = uriPrefix == null ? value : String.format("%s%s", uriPrefix, value);
        Link link = Link.fromUri(terminationUri).title(key + " URI").rel(key).type(MediaType.TEXT_PLAIN).build();

        if (b.length() != 0) {
            b.append(',');
        }

        return b.append(link);
    }

    private URI enlistCompensator(URI lraUri, Long timelimit, String uriPrefix,
            URI compensateUri, URI completeUri,
            URI forgetUri, URI leaveUri, URI afterUri, URI statusUri,
            StringBuilder compensatorData) {
        validateURI(completeUri, true, "Invalid complete URL: %s");
        validateURI(compensateUri, true, "Invalid compensate URL: %s");
        validateURI(leaveUri, true, "Invalid status URL: %s");
        validateURI(afterUri, true, "Invalid after URL: %s");
        validateURI(forgetUri, true, "Invalid forgetUri URL: %s");
        validateURI(statusUri, true, "Invalid status URL: %s");

        Map<String, URI> terminateURIs = new HashMap<>();

        terminateURIs.put(COMPENSATE, compensateUri);
        terminateURIs.put(COMPLETE, completeUri);
        terminateURIs.put(LEAVE, leaveUri);
        terminateURIs.put(AFTER, afterUri);
        terminateURIs.put(STATUS, statusUri);
        terminateURIs.put(FORGET, forgetUri);

        // register with the coordinator
        // put the lra id in an http header
        StringBuilder linkHeaderValue = new StringBuilder();

        terminateURIs.forEach((k, v) -> makeLink(linkHeaderValue, uriPrefix, k, v == null ? null : v.toASCIIString()));

        return enlistCompensator(lraUri, timelimit, linkHeaderValue.toString(), compensatorData);
    }

    public URI enlistCompensator(URI uri, Long timelimit, String linkHeader, StringBuilder compensatorData) {
        // register with the coordinator
        // put the lra id in an http header
        Client client = null;
        Response response = null;
        URL lraId = null;
        String data = compensatorData == null ? null : compensatorData.toString();

        try {
            lraId = uri.toURL();
        } catch (MalformedURLException mue) {
            throwGenericLRAException(null, Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(),
                    "Could not convert LRA to a URL : " + mue.getClass().getName() + ":" + mue.getMessage(), mue);
        }
        if (timelimit == null || timelimit < 0) {
            timelimit = 0L;
        }

        try {
            client = getClient();

            try {

                MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
                headers.add(NARAYANA_LRA_API_VERSION_HEADER_NAME, LRAConstants.CURRENT_API_VERSION_STRING);

                if (data != null) {
                    headers.add(NARAYANA_LRA_PARTICIPANT_DATA_HEADER_NAME, data);
                }

                headers.add("Link", linkHeader);
                response = client.target(coordinatorUrl)
                        .path(LRAConstants.getLRAUid(uri))
                        .queryParam(TIMELIMIT_PARAM_NAME, timelimit)
                        .request()
                        .headers(headers)
                        .async()
                        .put(Entity.text(compensatorData == null ? linkHeader : data))
                        .get(JOIN_TIMEOUT, TimeUnit.SECONDS);

                String responseEntity = response.hasEntity() ? response.readEntity(String.class) : "";
                // remove it and create tests for PRECONDITION_FAILED and NOT_FOUND
                if (response.getStatus() == Response.Status.PRECONDITION_FAILED.getStatusCode()) {
                    String logMsg = LRALogger.i18nLogger.error_tooLateToJoin(String.valueOf(lraId), responseEntity);
                    LRALogger.logger.error(logMsg);
                    throw new WebApplicationException(logMsg,
                            Response.status(PRECONDITION_FAILED).entity(logMsg).build());
                } else if (response.getStatus() == NOT_FOUND.getStatusCode()) {
                    String logMsg = LRALogger.i18nLogger.info_failedToEnlistingLRANotFound(
                            lraId, coordinatorUrl, NOT_FOUND.getStatusCode(), NOT_FOUND.getReasonPhrase(),
                            GONE.getStatusCode(), GONE.getReasonPhrase());
                    LRALogger.logger.info(logMsg);
                    throw new WebApplicationException(Response.status(GONE).entity(logMsg).build());
                } else if (response.getStatus() != OK.getStatusCode()) {
                    throw new WebApplicationException(responseEntity, response);
                }

                String recoveryUrl = null;
                String prevParticipantData = response.getHeaderString(NARAYANA_LRA_PARTICIPANT_DATA_HEADER_NAME);

                if (compensatorData != null && prevParticipantData != null) {
                    compensatorData.setLength(0);
                    compensatorData.append(prevParticipantData);
                }

                try {
                    recoveryUrl = response.getHeaderString(LRA_HTTP_RECOVERY_HEADER);
                    return new URI(recoveryUrl);
                } catch (URISyntaxException e) {
                    LRALogger.logger.infof(e, "join %s returned an invalid recovery URI '%s': %s", lraId, recoveryUrl,
                            responseEntity);
                    throwGenericLRAException(null, Response.Status.SERVICE_UNAVAILABLE.getStatusCode(),
                            "join " + lraId + " returned an invalid recovery URI '" + recoveryUrl + "' : " + responseEntity, e);
                    return null;
                }
                // don't catch WebApplicationException, just let it propagate
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                throw new WebApplicationException(Response.status(SERVICE_UNAVAILABLE)
                        .entity("join LRA client request timed out, try again later").build());
            }
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }

    private void endLRA(URI lra, boolean confirm, String compensator, String userData) throws WebApplicationException {
        Response response;

        lraTracef(lra, "%s LRA", confirm ? "close" : "compensate");

        try (Client client = getClient()) {
            try {
                URI uri = UriBuilder.fromUri(lra).replaceQuery(null).build();
                response = client.target(confirm ? String.format(CLOSE_PATH, uri) : String.format(CANCEL_PATH, uri))
                        .request()
                        .header(NARAYANA_LRA_API_VERSION_HEADER_NAME, LRAConstants.CURRENT_API_VERSION_STRING)
                        .header(NARAYANA_LRA_PARTICIPANT_LINK_HEADER_NAME, compensator)
                        .header(NARAYANA_LRA_PARTICIPANT_DATA_HEADER_NAME, userData)
                        .async()
                        .put(Entity.text(""))
                        .get(END_TIMEOUT, TimeUnit.SECONDS);
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                throw new WebApplicationException(Response.status(SERVICE_UNAVAILABLE)
                        .entity("end LRA client request timed out, try again later")
                        .build());
            }

            if (isUnexpectedResponseStatus(response, OK, Response.Status.ACCEPTED, NOT_FOUND)) {
                // let the client know the reason for the failure (it's in the entity body of the response object)
                throw new WebApplicationException(response);
            }

            if (response.getStatus() == NOT_FOUND.getStatusCode()) {
                throw new WebApplicationException(response);
            }

        } finally {
            Current.pop(lra);
            Current.removeActiveLRACache(lra);

        }
    }

    private void validateURI(URI uri, boolean nullAllowed, String message) {
        if (uri == null) {
            if (!nullAllowed) {
                throwGenericLRAException(null, NOT_ACCEPTABLE.getStatusCode(),
                        String.format(message, "null value"), null);
            }
        } else {
            try {
                // the passed in URI should be a valid URL - verify that that is the case
                uri.toURL();
            } catch (MalformedURLException mue) {
                throwGenericLRAException(null, NOT_ACCEPTABLE.getStatusCode(),
                        String.format(message, mue.getClass().getName() + ":" + mue.getMessage()) + " uri=" + uri, mue);
            }
        }
    }

    private boolean isUnexpectedResponseStatus(Response response, Response.Status... expected) {
        for (Response.Status anExpected : expected) {
            if (response.getStatus() == anExpected.getStatusCode()) {
                return false;
            }
        }
        return true;
    }

    public String getCoordinatorUrl() {
        // only used in the tests so return the default coordinator
        return coordinatorUrl.toString();
    }

    public String getRecoveryUrl() {
        // only used in the tests so use the default coordinator
        return getCoordinatorUrl() + "/" + RECOVERY_COORDINATOR_PATH_NAME;
    }

    public URI getCurrent() {
        return Current.peek();
    }

    public void clearCurrent(boolean all) {
        if (all) {
            Current.popAll();
        } else {
            Current.pop();
        }
    }

    private void lraTracef(String reasonFormat, Object... parameters) {
        if (LRALogger.logger.isTraceEnabled()) {
            LRALogger.logger.tracef(reasonFormat, parameters);
        }
    }

    private void lraTrace(URI lra, String reason) {
        if (LRALogger.logger.isTraceEnabled()) {
            lraTracef(lra, reason, (Object[]) null);
        }
    }

    private void lraTracef(URI lra, String reasonFormat, Object... parameters) {
        if (LRALogger.logger.isTraceEnabled()) {
            lraTracef(reasonFormat + ", lra id: %s", parameters, lra);
        }
    }

    /**
     * Shutdown any started resources (remark this method must be called if a config change is to take effect)
     */
    public void close() {
        if (storkInitialised) {
            Stork.shutdown();
        }
    }

    private void throwGenericLRAException(URI lraId, int statusCode, String message, Throwable cause)
            throws WebApplicationException {
        String errorMsg = String.format("%s: %s", lraId, message);
        throw new WebApplicationException(errorMsg, cause, Response.status(statusCode).entity(errorMsg).build());
    }

    private Client getClient() {
        return ClientBuilder.newClient();
    }
}
