
package com.asoroka.sidora.tabularmetadata.test;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;

import org.junit.experimental.theories.ParametersSuppliedBy;

import com.asoroka.sidora.tabularmetadata.datatype.DataType;

/**
 * Machinery for generating random test values.
 * 
 * @author ajs6f
 */
@Retention(RUNTIME)
@ParametersSuppliedBy(RandomValuesSupplier.class)
public @interface RandomValues {

    /**
     * Type of values to supply.
     */
    DataType type();

    /**
     * Number of values to supply.
     */
    short numValues();

}