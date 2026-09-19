package com.aegis.core.inventory.domain;

/**
 * How reachable a business service is from outside its own application — the owner's declaration of
 * its network posture, and the second input (with {@link BusinessCriticality}) that scoring reads off
 * the service itself.
 *
 * <p>This is a business decision, not something the platform can infer: only the service's owner
 * knows whether it terminates public traffic. It is deliberately coarse — three levels an owner can
 * choose confidently — because a finer scale invites guesses that scoring would then treat as fact.
 *
 * <p>Order is meaningful (least to most exposed) but the persisted form is the name, so entries may
 * be added; never reorder or rename them.
 */
public enum ServiceExposure {

    /** Reachable only from within its own application boundary — no inbound path from other systems. */
    ISOLATED,

    /** Reachable from other internal services, but not from the public internet. */
    INTERNAL,

    /** Terminates traffic from the public internet, directly or through an edge/CDN. */
    INTERNET_FACING
}
