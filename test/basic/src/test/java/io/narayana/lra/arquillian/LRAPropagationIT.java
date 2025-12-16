/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.arquillian;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.narayana.lra.arquillian.resource.LRAUnawareResource;
import io.narayana.lra.arquillian.resource.SimpleLRAParticipant;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.util.Optional;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.logging.Logger;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

public class LRAPropagationIT extends TestBase {
    private static final Logger log = Logger.getLogger(LRAPropagationIT.class);

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
        return Deployer.deploy(LRAPropagationIT.class.getSimpleName(), LRAUnawareResource.class, SimpleLRAParticipant.class)
                .addAsManifestResource(
                        new StringAsset("mp.lra.propagation.active=false"), "microprofile-config.properties");
    }

    @Test
    public void noLRATest() throws WebApplicationException {
        URI lraId = lraClient.startLRA(LRAPropagationIT.class.getName());

        URI returnedLraId = invokeInTransaction(baseURL,
                Response.Status.INTERNAL_SERVER_ERROR.getStatusCode());

        assertNotEquals(
                lraId, returnedLraId,
                "While calling non-LRA method the resource should not propagate the LRA id when mp.lra.propagation.active=false");

        lraClient.closeLRA(lraId);
    }

    @BeforeEach
    public void setup(TestInfo testInfo) {
        Optional<Method> testMethod = testInfo.getTestMethod();
        this.testName = testMethod.get().getName();
    }
}
