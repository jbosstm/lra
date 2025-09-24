/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra;

import java.net.URI;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class LRAConstantsTest {

    @Test
    public void getCoordinatorFromUsualLRAId() {
        URI lraId = URI.create("http://localhost:8080/lra-coordinator/0_ffff0a28054b_9133_5f855916_a7?query=1#fragment");
        URI coordinatorUri = LRAConstants.getLRACoordinatorUrl(lraId);
        Assertions.assertEquals("http", coordinatorUri.getScheme());
        Assertions.assertEquals("localhost", coordinatorUri.getHost());
        Assertions.assertEquals(8080, coordinatorUri.getPort());
        Assertions.assertEquals("/lra-coordinator", coordinatorUri.getPath());
        Assertions.assertNull(coordinatorUri.getQuery());
        Assertions.assertNull(coordinatorUri.getFragment());
        Assertions.assertEquals("http://localhost:8080/lra-coordinator", coordinatorUri.toASCIIString());
    }

    @Test
    public void getCoordinatorWithMultipleCoordinatorPaths() {
        URI lraId = URI.create("http://198.10.0.10:8999/lra-coordinator/lra-coordinator");
        URI coordinatorUri = LRAConstants.getLRACoordinatorUrl(lraId);
        Assertions.assertEquals("http://198.10.0.10:8999/lra-coordinator/lra-coordinator", coordinatorUri.toASCIIString());
    }
}
