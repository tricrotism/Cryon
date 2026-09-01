package com.tricrotism.cryon.common.signal

/**
 * A value that modules pass around and each other may change on the way past.
 *
 * Marker-only: implementing it says "this type travels the bus", which is what makes a `dispatch`
 * call's intent readable at the call site and stops arbitrary objects being broadcast by accident.
 */
interface Signal
