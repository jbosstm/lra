/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.logging;

import java.lang.invoke.MethodHandles;
import org.jboss.logging.Logger;

public final class LRALogger {
    private LRALogger() {
    }

    public static final Logger logger = Logger.getLogger("io.narayana.lra");
    public static final LraI18nLogger i18nLogger = Logger.getMessageLogger(MethodHandles.lookup(), LraI18nLogger.class,
            "io.narayana.lra");
}
