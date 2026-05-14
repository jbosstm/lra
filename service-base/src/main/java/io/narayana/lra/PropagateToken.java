/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indicates that the inbound JWT Bearer token should be propagated on outbound
 * LRA coordinator calls made during this method's execution.
 *
 * <p>
 * Place on a resource method alongside {@code @LRA} to enable automatic
 * token propagation without configuring {@code lra.http-client.providers}:
 * </p>
 *
 * <pre>
 * &#64;LRA(value = LRA.Type.REQUIRED)
 * &#64;PropagateToken
 * &#64;GET
 * public Response startWork() { ... }
 * </pre>
 *
 * <p>
 * Can also be placed on the class to apply to all {@code @LRA} methods.
 * </p>
 */
@Target({ ElementType.METHOD, ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
public @interface PropagateToken {
}
