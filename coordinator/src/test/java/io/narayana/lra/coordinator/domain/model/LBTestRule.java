/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */
package io.narayana.lra.coordinator.domain.model;

import java.lang.reflect.Field;
import java.util.Arrays;
import org.junit.AssumptionViolatedException;
import org.junit.rules.MethodRule;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.Statement;

/**
 * A JUnit4 rule to run parameterized test methods
 */
public class LBTestRule implements MethodRule {
    /**
     * The name of the field {@link LBTest#lb_method} to set depending on the values defined
     * in the {@link LBAlgorithms) annotation
     */
    private static final String LB_FIELD_NAME = "lb_method";

    private String methodName;

    @Override
    public Statement apply(Statement statement, FrameworkMethod method, Object target) {
        methodName = method.getName();
        LBAlgorithms lbAlgorithms = method.getAnnotation(LBAlgorithms.class);

        String foundLBMethodValue = findField(target, LB_FIELD_NAME);
        if (lbAlgorithms != null && foundLBMethodValue != null) {
            if (Arrays.stream(lbAlgorithms.value()).noneMatch(val -> val.equals(foundLBMethodValue))) {
                return new Statement() {
                    @Override
                    public void evaluate() {
                        throw new AssumptionViolatedException(
                                String.format("Test method %s is annotated with %s but the field '%s' "
                                        + "does not match any of the values defined in LBAlgorithms.value()) ",
                                        method.getName(), LBAlgorithms.class.getSimpleName(), LB_FIELD_NAME));
                    }
                };
            }
        }
        return statement;
    }

    public String getMethodName() {
        return methodName;
    }

    private String findField(Object object, String fieldName) {
        for (Field field : object.getClass().getDeclaredFields()) {
            if (field.getName().equals(fieldName) && field.getType().isAssignableFrom(String.class)) {
                try {
                    field.setAccessible(true);
                    return (String) field.get(object);
                } catch (IllegalAccessException iae) {
                    throw new IllegalStateException(
                            "Cannot get value of '" + fieldName + "' field with reflection",
                            iae);
                }
            }
        }
        return null;
    }
}
