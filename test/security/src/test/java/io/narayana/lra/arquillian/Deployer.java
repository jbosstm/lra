/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.arquillian;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;

public class Deployer {

    public static final String TEST_ISSUER = "lra-test-issuer";

    public static WebArchive deploy(String appName, Class... participants) {
        try {
            TestKeyManager.generateKeyPair();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate test key pair", e);
        }
        final String ManifestMF = "Manifest-Version: 1.0\n"
                + "Dependencies: org.jboss.jandex, org.jboss.logging, org.eclipse.microprofile.jwt.auth.api\n";

        final String mpJwtConfig = "mp.jwt.verify.publickey.location=META-INF/test-public-key.pem\n"
                + "mp.jwt.verify.issuer=" + TEST_ISSUER + "\n";

        return ShrinkWrap.create(WebArchive.class, appName + ".war")
                .addPackages(true,
                        "org.eclipse.microprofile.lra",
                        "io.narayana.lra.client.internal.proxy",
                        "io.smallrye.stork",
                        "io.smallrye.mutiny")
                .addPackages(false,
                        "io.narayana.lra",
                        "io.narayana.lra.logging",
                        "io.narayana.lra.filter",
                        "io.narayana.lra.provider",
                        "io.narayana.lra.context",
                        "io.narayana.lra.client",
                        "io.narayana.lra.client.internal",
                        "io.narayana.lra.arquillian.spi",
                        "io.smallrye.stork",
                        "io.smallrye.mutiny",
                        "io.smallrye.mutiny.helpers",
                        "io.smallrye.mutiny.operators.multi",
                        "io.smallrye.mutiny.subscription",
                        "io.smallrye.mutiny.helpers.spies",
                        "io.smallrye.mutiny.helpers.test")
                .addClass(TestBase.class)
                .addClass(io.narayana.lra.arquillian.resource.JwtTestApplication.class)
                .addClasses(participants)
                .addAsManifestResource(new StringAsset(ManifestMF), "MANIFEST.MF")
                .addAsWebInfResource(new StringAsset("<beans version=\"1.1\" bean-discovery-mode=\"all\"></beans>"),
                        "beans.xml")
                .addAsWebInfResource(new StringAsset(
                        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                                + "<web-app xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" version=\"6.0\">\n"
                                + "  <context-param>\n"
                                + "    <param-name>resteasy.role.based.security</param-name>\n"
                                + "    <param-value>true</param-value>\n"
                                + "  </context-param>\n"
                                + "</web-app>\n"),
                        "web.xml")
                .addAsResource(new StringAsset("org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder"),
                        "META-INF/services/jakarta.ws.rs.client.ClientBuilder")
                .addAsResource(new StringAsset("io.narayana.lra.context.ClientLRAContextProviderEE"),
                        "META-INF/services/jakarta.enterprise.concurrent.spi.ThreadContextProvider")

                // MP JWT configuration with runtime-generated public key
                .addAsResource(new StringAsset(mpJwtConfig), "META-INF/microprofile-config.properties")
                .addAsResource(new StringAsset(TestKeyManager.getPublicKeyPem()), "META-INF/test-public-key.pem");
    }
}
