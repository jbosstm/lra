/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.arquillian;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

public final class TestKeyManager {

    private static volatile RSAKey rsaKey;

    private TestKeyManager() {
    }

    public static synchronized void generateKeyPair() throws Exception {
        if (rsaKey != null) {
            return;
        }
        rsaKey = new RSAKeyGenerator(2048)
                .keyID("test-key-id")
                .generate();
    }

    public static String getPublicKeyPem() {
        try {
            RSAPublicKey publicKey = rsaKey.toRSAPublicKey();
            byte[] encoded = publicKey.getEncoded();
            return "-----BEGIN PUBLIC KEY-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(encoded)
                    + "\n-----END PUBLIC KEY-----\n";
        } catch (Exception e) {
            throw new RuntimeException("Failed to export public key as PEM", e);
        }
    }

    public static String createSignedToken(String subject, String... groups) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(Deployer.TEST_ISSUER)
                .subject(subject)
                .claim("upn", subject)
                .claim("groups", Arrays.asList(groups))
                .jwtID(UUID.randomUUID().toString())
                .issueTime(new Date())
                .expirationTime(new Date(System.currentTimeMillis() + 600_000))
                .build();

        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(rsaKey.getKeyID())
                .type(com.nimbusds.jose.JOSEObjectType.JWT)
                .build();

        SignedJWT signedJWT = new SignedJWT(header, claims);
        JWSSigner signer = new RSASSASigner(rsaKey);
        signedJWT.sign(signer);

        return signedJWT.serialize();
    }

    public static RSAKey getRsaKey() {
        return rsaKey;
    }
}
