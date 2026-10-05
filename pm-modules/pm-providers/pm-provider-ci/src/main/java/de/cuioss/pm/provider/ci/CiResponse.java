/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.provider.ci;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The result of one provider request.
 *
 * @param outcome the outcome
 * @param status  the HTTP status, {@code 0} when no response was received
 * @param body    the response body, empty when none
 * @param etag    the {@code ETag} of the response, for the next conditional request
 * @param next    the {@code rel="next"} target of the {@code Link} header
 * @param resetAt for {@link Outcome#RATE_LIMITED}: the instant from which the request may be re-issued
 * @param detail  the failure detail (never a secret)
 * @since 0.1
 */
public record CiResponse(Outcome outcome, int status, String body, Optional<String> etag, Optional<URI> next,
                         Optional<Instant> resetAt, String detail) {

    /** The closed outcome of a request. */
    public enum Outcome {
        /** A 2xx response. */
        OK,
        /** {@code 304 Not Modified} to a conditional request: the cached representation is current. */
        NOT_MODIFIED,
        /** The provider's rate limit applies; re-issue at {@code resetAt}. Neither a failure nor a timeout. */
        RATE_LIMITED,
        /** A 4xx or 5xx response other than a rate limit. */
        HTTP_ERROR,
        /** The request was refused before sending: target outside the origin, or a refused redirect hop. */
        REFUSED,
        /** No response: I/O failure, timeout or interruption. */
        TRANSPORT_FAILED
    }

    /**
     * @param outcome the outcome
     * @param status  the status
     * @param body    the body
     * @param etag    the etag
     * @param next    the next link
     * @param resetAt the reset instant
     * @param detail  the detail
     */
    public CiResponse {
        Objects.requireNonNull(outcome, "outcome");
        body = body == null ? "" : body;
        detail = detail == null ? "" : detail;
    }

    static CiResponse failure(Outcome outcome, String detail) {
        return new CiResponse(outcome, 0, "", Optional.empty(), Optional.empty(), Optional.empty(), detail);
    }

    /**
     * @return whether the outcome is {@link Outcome#OK}
     */
    public boolean isOk() {
        return outcome == Outcome.OK;
    }
}
