package com.example.ordermgt.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a handler parameter that receives the customer id of the signed-in session.
 *
 * <p>The id comes from the session's principal, never from a request header a client controls (the
 * old {@code X-Customer-Id} header let anyone act as any customer).
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentCustomer {}
