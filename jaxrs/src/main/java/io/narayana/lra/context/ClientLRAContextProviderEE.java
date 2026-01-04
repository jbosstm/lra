/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.context;

import io.narayana.lra.Current;
import io.narayana.lra.logging.LRALogger;
import jakarta.enterprise.concurrent.spi.ThreadContextProvider;
import jakarta.enterprise.concurrent.spi.ThreadContextSnapshot;
import jakarta.enterprise.inject.Vetoed;
import java.net.URI;
import java.util.Map;

@Vetoed // never a CDI bean
public class ClientLRAContextProviderEE implements ThreadContextProvider {

    private static final ThreadContextSnapshot NOOP_SNAPSHOT = () -> () -> {
    };

    @Override
    public ThreadContextSnapshot currentContext(Map<String, String> props) {
        URI lraId = Current.peek();
        if (lraId == null) {
            return NOOP_SNAPSHOT;
        }

        long originThreadId = Thread.currentThread().getId();

        return () -> {
            long executionThreadId = Thread.currentThread().getId();

            if (originThreadId != executionThreadId) {
                LRALogger.logger.debugf("Pushing %s from thread %d to %d", lraId, originThreadId, executionThreadId);

                // Executed in new Thread
                Current.push(lraId);
            }

            return () -> {
                long restorerThreadId = Thread.currentThread().getId();

                if (originThreadId != restorerThreadId && executionThreadId == restorerThreadId) {
                    // Executed in new Thread
                    URI oldLra = Current.pop();

                    LRALogger.logger.debugf("Popping %s obtained from thread %d in %d", oldLra, originThreadId,
                            restorerThreadId);
                }
            };

        };
    }

    @Override
    public ThreadContextSnapshot clearedContext(Map<String, String> props) {
        return NOOP_SNAPSHOT;
    }

    @Override
    public String getThreadContextType() {
        return "Lra-client";
    }

}
