/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.arquillian.appender;

import io.narayana.lra.arquillian.spi.NarayanaLRARecovery;
import java.util.Map;
import java.util.Optional;
import org.jboss.arquillian.config.descriptor.api.ArquillianDescriptor;
import org.jboss.arquillian.config.descriptor.api.ExtensionDef;
import org.jboss.arquillian.container.test.spi.client.deployment.AuxiliaryArchiveAppender;
import org.jboss.arquillian.core.api.Instance;
import org.jboss.arquillian.core.api.annotation.Inject;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;

/**
 * <p>
 * As the MicroProfile LRA TCK is implementation agnostic, deployments created within the TCK must be
 * enriched with dependencies, services and other needed properties/files in order to run the Narayana
 * implementation of the MicroProfile LRA specification. This class is an ad-hoc AuxiliaryArchiveAppender
 * developed exactly for this purpose and it can be activated specifying the activation class
 * {@link io.narayana.lra.arquillian.MpLraTckExtension}
 * in src/main/resources/META-INF/services/org.jboss.arquillian.core.spi.LoadableExtension. Moreover, this
 * extension is activated when an extension section is defined in arquillian.xml of the module
 * </p>
 * <p>
 * To activate this extension in your arquillian.xml, use the following construct:
 * </p>
 * <p>
 * {@code <extension qualifier="MpLraTckAppender"></extension>}
 * </p>
 */
public class MpLraTckAuxiliaryArchiveAppender implements AuxiliaryArchiveAppender {

    @Inject
    Instance<ArquillianDescriptor> arquillianDescriptorInstance;

    // manifest for WildFly deployment, it requires access to some other WildFly internal modules
    final String ManifestMF = "Manifest-Version: 1.0\n"
            + "Dependencies: org.jboss.jandex, org.jboss.logging, org.jboss.modules\n";

    public static final String EXTENSION_NAME = "MpLraTckAppender";

    @Override
    public Archive<?> createAuxiliaryArchive() {

        ArquillianDescriptor arquillianDescriptor = arquillianDescriptorInstance.get();

        Optional<Map<String, String>> checkExistence = arquillianDescriptor.getExtensions().stream()
                .filter(x -> x.getExtensionName().equals(EXTENSION_NAME))
                .map(ExtensionDef::getExtensionProperties).findAny();

        if (checkExistence.isEmpty()) {
            return null;
        }

        JavaArchive archive = ShrinkWrap.create(JavaArchive.class)
                // Loads dependencies
                .addPackages(false,
                        "io.narayana.lra",
                        "io.narayana.lra.logging",
                        "io.narayana.lra.filter",
                        "io.narayana.lra.provider",
                        "io.narayana.lra.client",
                        "io.narayana.lra.client.internal",
                        "org.eclipse.microprofile.lra")
                .addPackages(true,
                        "io.narayana.lra.client.internal.proxy",
                        "org.eclipse.microprofile.lra.annotation",
                        "io.smallrye.stork",
                        "io.smallrye.mutiny.helpers")
                // registration of LRACDIExtension as Weld extension to be booted-up
                .addAsResource("META-INF/services/jakarta.enterprise.inject.spi.Extension")

                // explicitly define to work with annotated beans
                .addAsManifestResource(new StringAsset("<beans version=\"1.1\" bean-discovery-mode=\"annotated\"></beans>"),
                        "beans.xml")

                // for WildFly we need dependencies to be part of the deployment's class path
                .addAsManifestResource(new StringAsset(ManifestMF), "MANIFEST.MF");

        archive.addPackages(true,
                io.narayana.lra.filter.ClientLRARequestFilter.class.getPackage(),
                io.narayana.lra.context.ClientLRAContextProviderEE.class.getPackage())
                .addAsResource(new StringAsset("org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder"),
                        "META-INF/services/jakarta.ws.rs.client.ClientBuilder")
                .addAsResource(new StringAsset("io.narayana.lra.context.ClientLRAContextProviderEE"),
                        "META-INF/services/jakarta.enterprise.concurrent.spi.ThreadContextProvider");

        // adding TCK required SPI implementation
        archive.addClass(NarayanaLRARecovery.class);
        archive.addAsResource(new StringAsset("io.narayana.lra.arquillian.spi.NarayanaLRARecovery"),
                "META-INF/services/org.eclipse.microprofile.lra.tck.service.spi.LRARecoveryService");

        return archive;
    }

}
