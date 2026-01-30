/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.client;

import io.narayana.lra.LRAConstants;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.concurrent.CompletionStage;

/**
 * MicroProfile REST Client interface for LRA Coordinator operations with asynchronous support.
 * This interface maps to the endpoints defined in io.narayana.lra.coordinator.api.Coordinator
 *
 * All methods return CompletionStage for asynchronous operations.
 *
 * This client is designed to be used programmatically via RestClientBuilder:
 *
 * <pre>
 * CoordinatorClient client = RestClientBuilder.newBuilder()
 *         .baseUri(coordinatorUri)
 *         .build(CoordinatorClient.class);
 * </pre>
 */
public interface CoordinatorClient {

    /**
     * Get all LRAs known to the coordinator
     *
     * @param status Filter LRAs by status (optional)
     * @param accept Media type for response
     * @param version API version header
     * @return Response containing list of LRAs
     */
    @GET
    @Path("/")
    @Produces({ MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN })
    CompletionStage<Response> getAllLRAs(
            @QueryParam(LRAConstants.STATUS_PARAM_NAME) @DefaultValue("") String status,
            @HeaderParam(HttpHeaders.ACCEPT) @DefaultValue(MediaType.TEXT_PLAIN) String accept,
            @HeaderParam(LRAConstants.NARAYANA_LRA_API_VERSION_HEADER_NAME) String version);

    /**
     * Get the status of a specific LRA
     *
     * @param lraId LRA identifier
     * @param accept Media type for response
     * @param version API version header
     * @return Response containing LRA status
     */
    @GET
    @Path("{LraId}/status")
    @Produces({ MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN })
    CompletionStage<Response> getLRAStatus(
            @PathParam("LraId") String lraId,
            @HeaderParam(HttpHeaders.ACCEPT) @DefaultValue(MediaType.TEXT_PLAIN) String accept,
            @HeaderParam(LRAConstants.NARAYANA_LRA_API_VERSION_HEADER_NAME) String version);

    /**
     * Get detailed information about a specific LRA
     *
     * @param lraId LRA identifier
     * @param accept Media type for response
     * @param version API version header
     * @return Response containing LRA data
     */
    @GET
    @Path("{LraId}")
    @Produces({ MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN })
    CompletionStage<Response> getLRAInfo(
            @PathParam("LraId") String lraId,
            @HeaderParam(HttpHeaders.ACCEPT) @DefaultValue(MediaType.TEXT_PLAIN) String accept,
            @HeaderParam(LRAConstants.NARAYANA_LRA_API_VERSION_HEADER_NAME) String version);

    /**
     * Start a new LRA
     *
     * @param clientId Client identifier
     * @param timeLimit Time limit in milliseconds
     * @param parentLRA Parent LRA identifier (for nested LRAs)
     * @param accept Media type for response
     * @param version API version header
     * @return Response with new LRA ID in Location header
     */
    @POST
    @Path("start")
    @Produces({ MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN })
    CompletionStage<Response> startLRA(
            @QueryParam(LRAConstants.CLIENT_ID_PARAM_NAME) @DefaultValue("") String clientId,
            @QueryParam(LRAConstants.TIMELIMIT_PARAM_NAME) @DefaultValue("0") Long timeLimit,
            @QueryParam(LRAConstants.PARENT_LRA_PARAM_NAME) @DefaultValue("") String parentLRA,
            @HeaderParam(HttpHeaders.ACCEPT) @DefaultValue(MediaType.TEXT_PLAIN) String accept,
            @HeaderParam(LRAConstants.NARAYANA_LRA_API_VERSION_HEADER_NAME) String version);

    /**
     * Renew the time limit for an existing LRA
     *
     * @param lraId LRA identifier
     * @param timeLimit New time limit in milliseconds
     * @param version API version header
     * @return Response indicating success or failure
     */
    @PUT
    @Path("{LraId}/renew")
    CompletionStage<Response> renewTimeLimit(
            @PathParam("LraId") String lraId,
            @QueryParam(LRAConstants.TIMELIMIT_PARAM_NAME) @DefaultValue("0") Long timeLimit,
            @HeaderParam(LRAConstants.NARAYANA_LRA_API_VERSION_HEADER_NAME) String version);

    /**
     * Join a participant to an LRA
     *
     * @param lraId LRA identifier
     * @param timeLimit Time limit for participant compensation
     * @param compensatorLink Link header containing participant endpoints
     * @param accept Media type for response
     * @param version API version header
     * @param participantData Participant-specific data
     * @param compensatorBody Compensator URL in body (deprecated)
     * @return Response with recovery URL in header
     */
    @PUT
    @Path("{LraId}")
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces({ MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN })
    CompletionStage<Response> joinLRA(
            @PathParam("LraId") String lraId,
            @QueryParam(LRAConstants.TIMELIMIT_PARAM_NAME) @DefaultValue("0") long timeLimit,
            @HeaderParam("Link") @DefaultValue("") String compensatorLink,
            @HeaderParam(HttpHeaders.ACCEPT) @DefaultValue(MediaType.TEXT_PLAIN) String accept,
            @HeaderParam(LRAConstants.NARAYANA_LRA_API_VERSION_HEADER_NAME) String version,
            @HeaderParam(LRAConstants.NARAYANA_LRA_PARTICIPANT_DATA_HEADER_NAME) @DefaultValue("") String participantData,
            String compensatorBody);

    /**
     * Remove a participant from an LRA
     *
     * @param lraId LRA identifier
     * @param accept Media type for response
     * @param version API version header
     * @param participantCompensatorUrl Participant compensator URL
     * @return Response indicating success or failure
     */
    @PUT
    @Path("{LraId}/remove")
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces({ MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN })
    CompletionStage<Response> leaveLRA(
            @PathParam("LraId") String lraId,
            @HeaderParam(HttpHeaders.ACCEPT) @DefaultValue(MediaType.TEXT_PLAIN) String accept,
            @HeaderParam(LRAConstants.NARAYANA_LRA_API_VERSION_HEADER_NAME) String version,
            String participantCompensatorUrl);

    /**
     * Close (complete) an LRA
     *
     * @param lraId LRA identifier
     * @param accept Media type for response
     * @param version API version header
     * @param compensator Compensator link header
     * @param userData User-specific data
     * @return Response containing LRA status
     */
    @PUT
    @Path("{LraId}/close")
    @Produces({ MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN })
    CompletionStage<Response> closeLRA(
            @PathParam("LraId") String lraId,
            @HeaderParam(HttpHeaders.ACCEPT) @DefaultValue(MediaType.TEXT_PLAIN) String accept,
            @HeaderParam(LRAConstants.NARAYANA_LRA_API_VERSION_HEADER_NAME) String version,
            @HeaderParam(LRAConstants.NARAYANA_LRA_PARTICIPANT_LINK_HEADER_NAME) @DefaultValue("") String compensator,
            @HeaderParam(LRAConstants.NARAYANA_LRA_PARTICIPANT_DATA_HEADER_NAME) @DefaultValue("") String userData);

    /**
     * Cancel (compensate) an LRA
     *
     * @param lraId LRA identifier
     * @param accept Media type for response
     * @param version API version header
     * @param compensator Compensator link header
     * @param userData User-specific data
     * @return Response containing LRA status
     */
    @PUT
    @Path("{LraId}/cancel")
    @Produces({ MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN })
    CompletionStage<Response> cancelLRA(
            @PathParam("LraId") String lraId,
            @HeaderParam(HttpHeaders.ACCEPT) @DefaultValue(MediaType.TEXT_PLAIN) String accept,
            @HeaderParam(LRAConstants.NARAYANA_LRA_API_VERSION_HEADER_NAME) String version,
            @HeaderParam(LRAConstants.NARAYANA_LRA_PARTICIPANT_LINK_HEADER_NAME) @DefaultValue("") String compensator,
            @HeaderParam(LRAConstants.NARAYANA_LRA_PARTICIPANT_DATA_HEADER_NAME) @DefaultValue("") String userData);

    /**
     * Get status of a nested LRA
     *
     * @param nestedLraId Nested LRA identifier
     * @return Response with nested LRA status
     */
    @GET
    @Path("nested/{NestedLraId}/status")
    CompletionStage<Response> getNestedLRAStatus(@PathParam("NestedLraId") String nestedLraId);

    /**
     * Complete a nested LRA
     *
     * @param nestedLraId Nested LRA identifier
     * @param accept Media type for response
     * @param version API version header
     * @return Response with participant status
     */
    @PUT
    @Path("nested/{NestedLraId}/complete")
    CompletionStage<Response> completeNestedLRA(
            @PathParam("NestedLraId") String nestedLraId,
            @HeaderParam(HttpHeaders.ACCEPT) @DefaultValue(MediaType.TEXT_PLAIN) String accept,
            @HeaderParam(LRAConstants.NARAYANA_LRA_API_VERSION_HEADER_NAME) String version);

    /**
     * Compensate a nested LRA
     *
     * @param nestedLraId Nested LRA identifier
     * @param accept Media type for response
     * @param version API version header
     * @return Response with participant status
     */
    @PUT
    @Path("nested/{NestedLraId}/compensate")
    CompletionStage<Response> compensateNestedLRA(
            @PathParam("NestedLraId") String nestedLraId,
            @HeaderParam(HttpHeaders.ACCEPT) @DefaultValue(MediaType.TEXT_PLAIN) String accept,
            @HeaderParam(LRAConstants.NARAYANA_LRA_API_VERSION_HEADER_NAME) String version);

    /**
     * Forget a nested LRA
     *
     * @param nestedLraId Nested LRA identifier
     * @return Response indicating success
     */
    @PUT
    @Path("nested/{NestedLraId}/forget")
    CompletionStage<Response> forgetNestedLRA(@PathParam("NestedLraId") String nestedLraId);
}
