/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.coordinator.domain.model.objectstore;

import static io.narayana.lra.LRAConstants.COORDINATOR_PATH_NAME;

import io.narayana.lra.LRAData;
import io.narayana.lra.client.NarayanaLRAClient;
import io.narayana.lra.coordinator.api.Coordinator;
import io.narayana.lra.filter.ServerLRAFilter;
import io.narayana.lra.logging.LRALogger;
import io.narayana.lra.provider.ParticipantStatusOctetStreamProvider;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.Application;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.lra.annotation.LRAStatus;
import org.jboss.resteasy.plugins.server.undertow.UndertowJaxrsServer;
import org.jboss.resteasy.test.TestPortProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;

public class TestBase {

    static UndertowJaxrsServer server;
    NarayanaLRAClient lraClient;
    Client client;

    public String testName;

    @ApplicationPath("/base")
    public static class LRAInfo extends Application {
        @Override
        public Set<Class<?>> getClasses() {
            HashSet<Class<?>> classes = new HashSet<>();
            classes.add(Coordinator.class);
            classes.add(ServerLRAFilter.class);
            classes.add(ParticipantStatusOctetStreamProvider.class);
            return classes;
        }
    }

    @ApplicationPath("/")
    public static class LRACoordinator extends Application {
        @Override
        public Set<Class<?>> getClasses() {
            HashSet<Class<?>> classes = new HashSet<>();
            classes.add(Coordinator.class);
            return classes;
        }
    }

    @BeforeAll
    static void start() {
        System.setProperty("lra.coordinator.url", TestPortProvider.generateURL('/' + COORDINATOR_PATH_NAME));
    }

    @BeforeEach
    public void before(TestInfo testInfo) {
        Optional<Method> testMethod = testInfo.getTestMethod();
        this.testName = testMethod.get().getName();
        LRALogger.logger.debugf("Starting test %s", testName);

        lraClient = new NarayanaLRAClient();

        client = ClientBuilder.newClient();

        server = new UndertowJaxrsServer().start();
        server.deploy(LRACoordinator.class);
        server.deploy(TestBase.LRAInfo.class);
    }

    @AfterEach
    public void after() {
        LRALogger.logger.debugf("Finished test %s", testName);

        lraClient.close();
        client.close();

        // Makes sure that all LRAs will be ended
        clearObjectStore();

        server.stop();
    }

    String convertLraUriToString(URI lraIdUri) {
        String lraIdString = lraIdUri.toString();
        return lraIdString.substring(lraIdString.lastIndexOf('/') + 1);
    }

    LRAStatus getStatus(URI lra) {
        try {
            return lraClient.getStatus(lra);
        } catch (NotFoundException ignore) {
            return null;
        }
    }

    LRAData getLastCreatedLRA() {

        List<LRAData> LRAList = new ArrayList<>();

        try {
            LRAList = lraClient.getAllLRAs();
        } catch (Exception ex) {
            LRALogger.logger.error(ex.getMessage());
        }

        return (LRAList.isEmpty() ? null : LRAList.get(LRAList.size() - 1));
    }

    void clearObjectStore() {

        try {
            List<LRAData> lraDataList = lraClient.getAllLRAs();

            for (LRAData lra : lraDataList) {
                lraClient.closeLRA(lra.getLraId());
            }
        } catch (WebApplicationException ex) {
            LRALogger.logger.error(ex.getMessage());
        }
    }
}
