/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.arquillian;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;

public class Deployer {

    public static WebArchive deploy(String appName, Class... participants) {
        // manifest for WildFly deployment
        final String ManifestMF = "Manifest-Version: 1.0\n"
                + "Dependencies: org.jboss.jandex, org.jboss.logging\n";

        return ShrinkWrap.create(WebArchive.class, appName + ".war")
                .addPackages(true,
                        "org.eclipse.microprofile.lra",
                        "io.narayana.lra.client.internal.proxy",
                        "io.smallrye.stork",
                        "io.smallrye.mutiny") // try to pull in everything under mutiny)
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
                        "io.smallrye.mutiny", // recursive addPacakges doesn't seem to resolve the missing classes, so:
                        "io.smallrye.mutiny.helpers",
                        "io.smallrye.mutiny.operators.multi",
                        "io.smallrye.mutiny.subscription",
                        "io.smallrye.mutiny.helpers.spies",
                        "io.smallrye.mutiny.helpers.test")

                // adds the TestBase class, the test class itself seems to be uploaded during deployment by Arquillian
                // then it requires the parent class as well, otherwise Weld NoClassDefFoundError is shown
                .addClass(TestBase.class)

                // adds the lra-participant wanted
                .addClasses(participants)

                // activates Wildfly modules
                .addAsManifestResource(new StringAsset(ManifestMF), "MANIFEST.MF")

                // activates the bean and explicitly specifies to work with annotated classes
                .addAsWebInfResource(new StringAsset("<beans version=\"1.1\" bean-discovery-mode=\"all\"></beans>"),
                        "beans.xml")

                // services from narayana-lra.jar
                .addAsResource(new StringAsset("org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder"),
                        "META-INF/services/jakarta.ws.rs.client.ClientBuilder")
                .addAsResource(new StringAsset("io.narayana.lra.context.ClientLRAContextProviderEE"),
                        "META-INF/services/jakarta.enterprise.concurrent.spi.ThreadContextProvider");
    }
}
