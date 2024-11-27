package io.narayana.lra.coordinator.management;

import io.narayana.lra.coordinator.domain.model.FailedLongRunningAction;
import io.narayana.lra.coordinator.domain.model.LongRunningAction;
import org.eclipse.microprofile.lra.annotation.LRAStatus;
import org.eclipse.microprofile.lra.annotation.ParticipantStatus;
import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URL;

public class BrowserCommandTest {
    private static final String TEST_STORE = "test-store";
    private static final String TEST_COMMANDS = "browser-commands.txt";
    private static final String LRA_UID = "0_ffffc0a801c7_afa5_6745e313_2";
    private static final String FAILED_LRA_UID = "0_ffffc0a801dd_407cfb77_6748b27e_1b7";

    @Test
    public void testStart() throws Exception {

        StringBuilder sb = new StringBuilder();
        InputStream cmdSource = new ByteArrayInputStream(sb.toString().getBytes());
        URL resource = getClass().getClassLoader().getResource(TEST_STORE);
        Assert.assertNotNull(resource);
        String storeDir = resource.getFile();
        URL commands = getClass().getClassLoader().getResource(TEST_COMMANDS);
        Assert.assertNotNull(commands);
        String testCommands = commands.getFile();

        File f1 = new File(storeDir);
        Assert.assertTrue(f1.exists() && f1.isDirectory());
        File f2 = new File(testCommands);
        Assert.assertTrue(f2.exists() && f2.isFile());

        String[] args = new String[] {
                "-s", storeDir,
                "-f", testCommands
        };

        String output = BrowserCommand.run(args);

        // the store in the test resources directory contains an LRA in state Cancelling
        Assert.assertTrue(output.contains(LongRunningAction.getType()
                .substring(1))); // TODO find out where and why the instrumentation strips the initial slash

        Assert.assertTrue(output.contains(LRA_UID));
        Assert.assertTrue(output.contains(LRAStatus.Cancelling.name()));

        // because one of the participants is still Compensating
        Assert.assertTrue(output.contains(ParticipantStatus.Compensating.name()));

        // and the store in the test resources directory contains an LRA in state FailedToCancel
        Assert.assertTrue(output.contains(FailedLongRunningAction.getType()
                .substring(1)));

        Assert.assertTrue(output.contains(FAILED_LRA_UID));
        Assert.assertTrue(output.contains(LRAStatus.FailedToCancel.name()));

        // because one of the participants FailedToCompensate
        Assert.assertTrue(output.contains(ParticipantStatus.FailedToCompensate.name()));

        System.out.printf(output); // the actual output from the browser
    }
}
