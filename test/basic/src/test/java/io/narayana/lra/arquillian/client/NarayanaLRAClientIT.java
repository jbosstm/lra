/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.arquillian.client;

import io.narayana.lra.LRAData;
import io.narayana.lra.arquillian.TestBase;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

public class NarayanaLRAClientIT extends TestBase {

    private static final Logger log = Logger.getLogger(NarayanaLRAClientIT.class);

    public String testName;

    @BeforeEach
    public void before(TestInfo testInfo) {
        testName = testInfo.getDisplayName();
        log.info("Running test " + testName);
    }

    @Test
    public void testGetAllLRAs() {
        URI lra = lraClient.startLRA("test-lra");
        lrasToAfterFinish.add(lra);

        List<LRAData> allLRAs = lraClient.getAllLRAs();
        Assertions.assertTrue(allLRAs.stream().anyMatch(lraData -> lraData.getLraId().equals(lra)),
                "Expected to find the LRA " + lra + " amongst all active ones: " + allLRAs);

        lraClient.closeLRA(lra);

        allLRAs = lraClient.getAllLRAs();
        Assertions.assertTrue(allLRAs.stream().noneMatch(lraData -> lraData.getLraId().equals(lra)),
                "LRA " + lra + " was closed but is still referred as active one at: " + allLRAs);
    }

    @BeforeEach
    public void setup(TestInfo testInfo) {
        Optional<Method> testMethod = testInfo.getTestMethod();
        this.testName = testMethod.get().getName();
    }

}
