/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.coordinator.domain.model.objectstore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.arjuna.ats.arjuna.common.ObjectStoreEnvironmentBean;
import com.arjuna.ats.internal.arjuna.objectstore.jdbc.JDBCStore;
import com.arjuna.common.internal.util.propertyservice.BeanPopulator;
import io.narayana.lra.LRAData;
import io.narayana.lra.logging.LRALogger;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.microprofile.lra.annotation.LRAStatus;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class JDBCObjectStoreTest extends TestBase {

    @BeforeAll
    public static void start() {
        TestBase.start();
        System.setProperty("com.arjuna.ats.arjuna.common.propertiesFile", "h2jbossts-properties.xml");
    }

    /**
     * This test checks that a new LRA transaction can be created when
     * Narayana is configured to use a JDBC Object Store. This test fails
     * if the Object Store is not set to JDBCStore
     */
    @Test
    public void jdbcStoreTest() {

        String objectStoreType = BeanPopulator.getDefaultInstance(ObjectStoreEnvironmentBean.class).getObjectStoreType();
        // This test fails if the Object Store is not set to JDBCStore
        assertEquals(JDBCStore.class.getName(), objectStoreType, "The Object Store type should have been set to JDBCStore");

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

        // Connecting to the database to double check that everything is fine
        String jdbcAccess = BeanPopulator.getDefaultInstance(ObjectStoreEnvironmentBean.class).getJdbcAccess();
        Pattern pattern = Pattern.compile(".*URL=(.*);User=(.*);Password=(.*).*");
        Matcher matcher = pattern.matcher(jdbcAccess);
        // In case the RegEx pattern does not work
        Assertions.assertTrue(
                matcher.find(),
                String.format("The Arjuna's JDBCAccess string:\n %s\n is not formatted as it should", jdbcAccess));

        try (Connection conn = DriverManager.getConnection(matcher.group(1), matcher.group(2), matcher.group(3))) {

            String tablePrefix = BeanPopulator.getDefaultInstance(ObjectStoreEnvironmentBean.class).getTablePrefix();
            Statement st = conn.createStatement();

            // Simple SQL statement to fetch all data from the (PREFIX)JBOSSTSTXTABLE
            ResultSet resultSet = st.executeQuery("SELECT * FROM " +
                    (Objects.isNull(tablePrefix) ? "" : tablePrefix) +
                    "JBOSSTSTXTABLE");

            // Fetches all info from the first row of the ResultSet
            resultSet.first();
            int dbLraStatus = resultSet.getInt(2);
            String dbLraType = resultSet.getString(3);
            String dbLraId = resultSet.getString(4); // Column where the LRA ID is

            // Checks that the status of the LRA found in the database is ACTIVE
            assertTrue(dbLraType.contains("LongRunningAction"),
                    "Expected that the database holds a Long Running Action transaction");

            // Checks that the ID of the LRA created previously is equal to the ID of the LRA found in the database
            assertEquals(
                    dbLraId,
                    lraId,
                    String.format("Expected that the database holds an LRA with ID %s", lraId));

            // Checks that the status of the LRA found in the database is ACTIVE
            assertEquals(LRAStatus.Active.ordinal(),
                    dbLraStatus,
                    "Expected that the database holds an active LRA");

        } catch (SQLException sqlException) {
            LRALogger.logger.errorf("%s: %s", testName, sqlException.getMessage());
            fail(sqlException.getMessage());
        }
    }
}
