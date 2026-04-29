/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.arquillian;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.narayana.lra.arquillian.resource.JwtParticipant;
import jakarta.ws.rs.core.Response;
import java.net.URL;
import java.util.Arrays;
import java.util.Date;
import java.util.logging.Logger;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

/**
 * Integration tests verifying JWT CDI integration: that the container's MP JWT
 * implementation makes {@code JsonWebToken} available via CDI, validates token
 * signatures, and enforces issuer claims.
 *
 * <p>
 * Requires the {@code -Parq} Maven profile to start WildFly containers.
 */
public class JwtCdiIntegrationIT extends TestBase {

    private static final Logger log = Logger.getLogger(JwtCdiIntegrationIT.class.getName());

    @ArquillianResource
    public URL baseURL;

    private String testName;

    @Deployment
    public static WebArchive deploy() {
        return Deployer.deploy(JwtCdiIntegrationIT.class.getSimpleName(), JwtParticipant.class);
    }

    @BeforeAll
    public static void setupKeys() throws Exception {
        TestKeyManager.generateKeyPair();
    }

    @BeforeEach
    public void before(TestInfo testInfo) {
        testName = testInfo.getDisplayName();
        log.info("Running test " + testName);
    }

    @Test
    public void testJsonWebTokenInjectionIsResolvableWithoutToken() {
        try (Response response = client.target(baseURL.toExternalForm())
                .path(JwtParticipant.ROOT_PATH)
                .path(JwtParticipant.CDI_TOKEN_PATH)
                .request()
                .get()) {
            assertEquals(200, response.getStatus(),
                    "CDI Instance<JsonWebToken> should not cause deployment or request failure");
            String rawToken = response.readEntity(String.class);
            assertTrue(rawToken.isEmpty(),
                    "Without a Bearer token in the request, getRawToken() should return null/empty");
        }
    }

    @Test
    public void testJsonWebTokenReturnsSignedToken() throws Exception {
        String token = TestKeyManager.createSignedToken("test-user", "user");

        try (Response response = client.target(baseURL.toExternalForm())
                .path(JwtParticipant.ROOT_PATH)
                .path(JwtParticipant.CDI_TOKEN_PATH)
                .request()
                .header("Authorization", "Bearer " + token)
                .get()) {
            assertEquals(200, response.getStatus());
            String rawToken = response.readEntity(String.class);
            assertFalse(rawToken.isEmpty(),
                    "With a valid signed Bearer token, JsonWebToken.getRawToken() should return it");
            assertEquals(token, rawToken);
        }
    }

    @Test
    public void testWrongIssuerTokenIsNotAccepted() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("wrong-issuer")
                .subject("test-user")
                .claim("upn", "test-user")
                .claim("groups", Arrays.asList("user"))
                .issueTime(new Date())
                .expirationTime(new Date(System.currentTimeMillis() + 600_000))
                .build();

        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(TestKeyManager.getRsaKey().getKeyID())
                .type(com.nimbusds.jose.JOSEObjectType.JWT)
                .build();

        SignedJWT signedJWT = new SignedJWT(header, claims);
        signedJWT.sign(new RSASSASigner(TestKeyManager.getRsaKey()));

        String badToken = signedJWT.serialize();

        try (Response response = client.target(baseURL.toExternalForm())
                .path(JwtParticipant.ROOT_PATH)
                .path(JwtParticipant.CDI_TOKEN_PATH)
                .request()
                .header("Authorization", "Bearer " + badToken)
                .get()) {
            assertEquals(401, response.getStatus(),
                    "Token with wrong issuer should be rejected by the MP JWT runtime");
        }
    }
}
