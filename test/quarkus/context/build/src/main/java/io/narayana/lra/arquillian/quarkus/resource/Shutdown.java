/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.arquillian.quarkus.resource;

import io.quarkus.runtime.Quarkus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

@ApplicationScoped
@Path("")
public class Shutdown {

    @GET
    @Path("/shutdown")
    public Response shutdown() {
        Quarkus.asyncExit(); // triggers Quarkus shutdown

        return Response.ok().build();
    }

}
