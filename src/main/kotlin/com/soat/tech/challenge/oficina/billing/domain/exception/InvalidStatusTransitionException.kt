package com.soat.tech.challenge.oficina.billing.domain.exception

/** Mapped to 409 by the RestExceptionHandler (DomainException). */
class InvalidStatusTransitionException(
    val currentStatus: String,
    val action: String,
) : DomainException("Invalid transition: status=$currentStatus, action=$action")
