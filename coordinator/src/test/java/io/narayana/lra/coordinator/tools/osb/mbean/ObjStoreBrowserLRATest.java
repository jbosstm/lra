/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.coordinator.tools.osb.mbean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.arjuna.ats.arjuna.common.recoveryPropertyManager;
import com.arjuna.ats.arjuna.tools.osb.mbean.ActionBean;
import com.arjuna.ats.arjuna.tools.osb.mbean.LogRecordWrapper;
import com.arjuna.ats.arjuna.tools.osb.mbean.OSBTypeHandler;
import com.arjuna.ats.arjuna.tools.osb.mbean.OSEntryBean;
import com.arjuna.ats.arjuna.tools.osb.mbean.ObjStoreBrowser;
import com.arjuna.ats.arjuna.tools.osb.mbean.UidWrapper;
import com.arjuna.ats.internal.arjuna.recovery.RecoveryManagerImple;
import io.narayana.lra.coordinator.domain.model.FailedLongRunningAction;
import io.narayana.lra.coordinator.domain.model.LRAParticipantRecord;
import io.narayana.lra.coordinator.domain.model.LongRunningAction;
import io.narayana.lra.coordinator.internal.Implementations;
import io.narayana.lra.coordinator.internal.LRARecoveryModule;
import java.io.File;
import java.net.URI;
import org.eclipse.microprofile.lra.annotation.LRAStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ObjStoreBrowserLRATest {
    private RecoveryManagerImple recoveryManager;
    private ObjStoreBrowser osb;

    private static final String[][] LRA_OSB_TYPES = {
            // osTypeClassName, beanTypeClassName - see com.arjuna.ats.arjuna.tools.osb.mbean.ObjStoreBrowser
            { LongRunningAction.getType().substring(1), LongRunningAction.class.getName(), LRAActionBean.class.getName() },
            { FailedLongRunningAction.getType().substring(1), FailedLongRunningAction.class.getName(),
                    LRAActionBean.class.getName() }
    };

    @BeforeEach
    public void setUp() {
        recoveryPropertyManager.getRecoveryEnvironmentBean().setRecoveryBackoffPeriod(1);
        Implementations.install();
        recoveryManager = new RecoveryManagerImple(false);
        recoveryManager.addModule(new LRARecoveryModule());

        // initiating the ObjStoreBrowser
        osb = ObjStoreBrowser.getInstance();
        for (String[] typeAndBean : LRA_OSB_TYPES) {
            String typeName = typeAndBean[0].replace("/", File.separator);
            osb.addOSBTypeHandler(typeName, new OSBTypeHandler(true, true, typeAndBean[1], typeAndBean[2],
                    typeAndBean[0], null, this.getClass().getClassLoader()));
        }
        osb.start();
    }

    @AfterEach
    public void tearDown() {
        recoveryManager.removeAllModules(false);
        recoveryManager.stop(false);
        Implementations.uninstall();

        osb.stop();
    }

    @Test
    public void lraMBean() throws Exception {
        String lraUrl = "http://localhost:8080/lra";

        LongRunningAction lra = LRARecoveryModule.getService()
                .startLRA(lraUrl, null, "client", Long.MAX_VALUE);

        osb.probe();
        UidWrapper uidWrapper = osb.findUid(lra.get_uid());
        assertEquals(lra.get_uid(), uidWrapper.getUid(), "Probed LRA uid has to be equal to what the LRA was created with");

        LRARecoveryModule.getService()
                .endLRA(lra.getId(), false, false);

        osb.probe();
        uidWrapper = osb.findUid(lra.get_uid());
        assertNull(uidWrapper, "Expected the LRA records were removed");

        osb.stop();
    }

    @Test
    public void lraMBeanRemoval() throws Exception {
        String lraUrl = "http://localhost:8080/lra";
        LongRunningAction lra = LRARecoveryModule.getService().startLRA(lraUrl, null, "client", Long.MAX_VALUE);
        OSEntryBean lraOSEntryBean = null;
        try {
            lra.begin(Long.MAX_VALUE); // Creating the LRA records in the log store.
            String coordinatorUrl = "http://localhost:8080/lra-coordinator";
            String participantUrl = "http://localhost:8080/lra-participant";
            LRAParticipantRecord lraParticipant = lra.enlistParticipant(URI.create(coordinatorUrl), participantUrl,
                    "/recover", Long.MAX_VALUE, null, null);

            osb.probe();

            UidWrapper uidWrapper = osb.findUid(lra.get_uid());
            assertNotNull(uidWrapper, "Expected the LRA MBean uid was probed");
            lraOSEntryBean = uidWrapper.getMBean();
            assertNotNull(lraOSEntryBean, "Expecting the UID to contain the LRA mbean");
            assertTrue(
                    lraOSEntryBean instanceof ActionBean,
                    "The mbean should wrap " + ActionBean.class.getName() + " but it's " + lraOSEntryBean.getClass().getName());
            ActionBean actionBean = (ActionBean) lraOSEntryBean;
            assertEquals(1, actionBean.getParticipants().size(), "One participant was enlisted");
            LogRecordWrapper logRecord = actionBean.getParticipants().iterator().next();
            assertTrue(logRecord instanceof LRAParticipantRecordWrapper, "The log wrapper needs to be from LRA");
            LRAParticipantRecordWrapper lraRecord = (LRAParticipantRecordWrapper) logRecord;
            Assertions.assertEquals(LRAStatus.Active.name(), lraRecord.getLRAStatus(), "Participant should be active");
            Assertions.assertEquals(participantUrl + "/compensate", lraRecord.getCompensator(),
                    "Compensator URI is expected as it was registered with '/compensate' suffix");
        } finally {
            // this removal is part of the test where we check that remove on OS bean works in later check
            lraOSEntryBean.remove(false);
        }

        osb.probe();

        UidWrapper uidWrapper = osb.findUid(lra.get_uid());
        assertNull(uidWrapper, "Expected the LRA records were removed");
    }

    @Test
    public void lraFailedMBean() throws Exception {
        String lraUrl = "http://localhost:8080/lra";

        LongRunningAction lra = LRARecoveryModule.getService()
                .startLRA(lraUrl, null, "client", Long.MAX_VALUE);

        // LongRunningAction -> FailedLongRunningAction
        LRARecoveryModule.getInstance()
                .moveEntryToFailedLRAPath(lra.get_uid());

        osb.probe();
        UidWrapper uidWrapper = osb.findUid(lra.get_uid());
        assertEquals(lra.get_uid(), uidWrapper.getUid(), "Probed LRA uid has to be equal to what the LRA was created with");

        OSEntryBean lraOSEntryBean = uidWrapper.getMBean();
        assertNotNull(lraOSEntryBean, "Expecting the UID wrapper to contain the LRA mbean");
        assertTrue(lraOSEntryBean instanceof LRAActionBean, "The provided jmx mbean should wrap LRAActionBean");
        LRAActionBean lraActionBean = (LRAActionBean) lraOSEntryBean;

        Assertions.assertEquals(noSlash(lraActionBean.type()), noSlash(FailedLongRunningAction.FAILED_LRA_TYPE),
                "The probed action bean should be of type 'Failed LRA'");

        assertFalse(LRARecoveryModule.getInstance().removeCommitted(uidWrapper.getUid()),
                "Expected the recovery module cannot remove the failed LRA by Uid");
        uidWrapper = osb.findUid(lra.get_uid());
        assertNotNull(uidWrapper, "Failed LRA record should exist in log");

        lraOSEntryBean.remove(true);
        uidWrapper = osb.findUid(lra.get_uid());
        assertNull(uidWrapper, "After removal the Failed LRA record should not exist in log anymore");
    }

    private String noSlash(String type) {
        if (type == null || type.length() == 0) {
            return type;
        }
        char firstChar = type.charAt(0);
        return firstChar == '/' ? type.substring(1) : type;
    }
}
