/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.coordinator.domain.model.objectstore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.arjuna.ats.arjuna.common.ObjectStoreEnvironmentBean;
import com.arjuna.ats.internal.arjuna.objectstore.VolatileStore;
import com.arjuna.common.internal.util.propertyservice.BeanPopulator;
import io.narayana.lra.LRAData;
import io.narayana.lra.logging.LRALogger;
import java.net.URI;
import org.eclipse.microprofile.lra.annotation.LRAStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class VolatileObjectStoreTest extends TestBase {

    @BeforeAll
    public static void start() {
        TestBase.start();
        System.setProperty("com.arjuna.ats.arjuna.common.propertiesFile", "alt-jbossts-properties.xml");
    }

    /**
     * This test checks that a new LRA transaction can be created when
     * Narayana is configured to use a Volatile Object Store. This test
     * fails if the Object Store is not set to VolatileStore
     */
    @Test
    public void volatileStoreTest() {

        String objectStoreType = BeanPopulator.getDefaultInstance(ObjectStoreEnvironmentBean.class).getObjectStoreType();
        // This test fails if the Object Store is not set to Volatile
        assertEquals(VolatileStore.class.getName(),
                objectStoreType,
                "The Object Store type should have been set to VolatileStore");

        LRALogger.logger.infof("%s: the Object Store type is set to: %s", testName, objectStoreType);

        // Starts a new LRA
        URI lraIdUri = lraClient.startLRA(testName + "#newLRA");
        // Checks that the LRA transaction has been created
        assertNotNull(lraIdUri, "An LRA should have been added to the object store");
        // Using NarayanaLRAClient, the following statement checks that the status of the new LRA is active
        assertEquals(LRAStatus.Active, getStatus(lraIdUri), "Expected Active");

        // Extracts the id from the URI
        String lraId = convertLraUriToString(lraIdUri).replace('_', ':');

        LRAData lraData = getLastCreatedLRA();
        assertEquals(
                lraData.getLraId(),
                lraIdUri,
                "Expected that the LRA transaction just started matches the LRA transaction fetched through the Narayana LRA client");
    }
}
