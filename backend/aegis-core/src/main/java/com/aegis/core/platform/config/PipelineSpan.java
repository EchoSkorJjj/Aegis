package com.aegis.core.platform.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A pipeline span with optional UUID correlation fields. Arguments and results are never logged. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PipelineSpan {
    String value();
    int signalArgument() default -1;
    int serviceArgument() default -1;
}
