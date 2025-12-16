/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.arquillian;

import static org.eclipse.microprofile.lra.annotation.LRAStatus.Closing;
import static org.junit.jupiter.api.Assertions.fail;

import io.narayana.lra.arquillian.resource.NonRootLRAParticipant;
import io.narayana.lra.arquillian.resource.RootResource;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Optional;
import org.eclipse.microprofile.lra.annotation.LRAStatus;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.logging.Logger;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

public class NonRootLRAParticipantIT extends TestBase {
    private static final Logger log = Logger.getLogger(NonRootLRAParticipantIT.class);

    @ArquillianResource
    public URL baseURL;

    public String testName;

    @BeforeEach
    public void before(TestInfo testInfo) {
        testName = testInfo.getDisplayName();
        log.info("Running test " + testName);
    }

    @Deployment
    public static WebArchive deploy() {
        return Deployer.deploy(NonRootLRAParticipantIT.class.getSimpleName(), RootResource.class, NonRootLRAParticipant.class);
    }

    @Test
    public void testNonRootLRAParticipantEnlist() {

        Response response = client.target(baseURL.toExternalForm() + "root/participant/lra").request().get();

        Assertions.assertEquals(Response.Status.PRECONDITION_FAILED.getStatusCode(), response.getStatus());

        response = client.target(baseURL.toExternalForm() + "root/participant/counter").request().get();
        Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        int counterValue = response.readEntity(Integer.class);
        Assertions.assertEquals(1, counterValue, "Non root JAX-RS participant should have been enlisted and invoked");
    }

    /*
     * Test that a participant can join an LRA using the Narayana specific client API
     * Test that the LRA has the correct status of Closing if a participant
     * returns 202 Accepted when asked to complete.
     *
     * @throws URISyntaxException if arquillian URL is invalid
     */
    @Test
    public void testFinishLRA() throws URISyntaxException {

        URI lraId = lraClient.startLRA("testFinishLRA");
        lrasToAfterFinish.add(lraId);
        URI compensateURI = new URI(baseURL.toExternalForm() + "/participant/compensate");
        URI completeURI = new URI(baseURL.toExternalForm() + "/participant/complete");

        // enlist the participant with these compensate and complete URIs into the LRA
        lraClient.joinLRA(lraId, 0L, compensateURI, completeURI, null, null, null, null, (StringBuilder) null);
        lraClient.closeLRA(lraId);

        try {
            LRAStatus status = lraClient.getStatus(lraId);

            Assertions.assertEquals(Closing, status, "wrong state");
        } catch (WebApplicationException e) {
            fail("testFinishLRA: received unexpected response code (" + e.getResponse().getStatus()
                    + ") getting LRA status " + e.getMessage());
        }
    }

    @BeforeEach
    public void setup(TestInfo testInfo) {
        Optional<Method> testMethod = testInfo.getTestMethod();
        this.testName = testMethod.get().getName();
    }
}
