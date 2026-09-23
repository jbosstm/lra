/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.arquillian;

import io.narayana.lra.proxy.test.api.JaxRsActivator;
import io.narayana.lra.proxy.test.api.LRAMgmtEgController;
import io.narayana.lra.proxy.test.model.Activity;
import io.narayana.lra.proxy.test.model.Participant;
import io.narayana.lra.proxy.test.service.ActivityService;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.jboss.shrinkwrap.resolver.api.maven.Maven;

public class Deployer {

    /**
     * Builds the proxy test application. It is deployed as {@code ROOT.war} (root context) so that the proxy
     * participant callback endpoints registered with the coordinator by
     * {@link io.narayana.lra.client.internal.proxy.ProxyService} resolve without a context-path prefix.
     */
    public static WebArchive createDeployment() {
        String resteasyClientVersion = System.getProperty("version.resteasy-client");
        String eclipseLraVersion = System.getProperty("version.microprofile.lra");
        String projectVersion = System.getProperty("project.version");

        return ShrinkWrap.create(WebArchive.class, "ROOT.war")

                .addPackages(true,
                        "io.smallrye.stork",
                        "io.smallrye.mutiny")

                // the proxy test application (non-JAX-RS participant registered through the ProxyService)
                .addClasses(JaxRsActivator.class, LRAMgmtEgController.class, ActivityService.class,
                        Activity.class, Participant.class)

                // support libraries resolved from the Maven repositories
                .addAsLibraries(Maven.resolver()
                        .resolve("org.jboss.resteasy:resteasy-client:" + resteasyClientVersion,
                                "org.eclipse.microprofile.lra:microprofile-lra-api:" + eclipseLraVersion)
                        .withoutTransitivity().asFile())

                // support libraries from the local Maven store (built earlier in the reactor); lra-proxy-api brings
                // in ProxyService, ParticipantProxyResource and the LRACDIExtension via its META-INF/services entry
                .addAsLibraries(Maven.configureResolver()
                        .workOffline()
                        .withMavenCentralRepo(false)
                        .withClassPathResolution(true)
                        .resolve("org.jboss.narayana.lra:lra-service-base:" + projectVersion,
                                "org.jboss.narayana.lra:lra-proxy-api:" + projectVersion,
                                "org.jboss.narayana.lra:lra-client:" + projectVersion,
                                "org.jboss.narayana.lra:narayana-lra:" + projectVersion)
                        .withoutTransitivity().asFile())

                // activate the jandex and logging WildFly modules
                .addAsManifestResource(
                        new StringAsset("Dependencies: org.jboss.jandex, org.jboss.logging\n"),
                        "MANIFEST.MF")

                // enable CDI with full discovery so the JAX-RS resource and the ActivityService bean are picked up
                .addAsWebInfResource(
                        new StringAsset("<beans version=\"1.1\" bean-discovery-mode=\"all\"></beans>"),
                        "beans.xml");
    }
}
