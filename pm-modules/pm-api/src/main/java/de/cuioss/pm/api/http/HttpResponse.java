/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.api.http;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * A completely read HTTP response.
 *
 * @param status      the status code
 * @param contentType the {@code Content-Type}, {@code null} when absent
 * @param body        the body bytes
 */
public record HttpResponse(int status, String contentType, byte[] body) {

    /**
     * Copies the body.
     *
     * @param status      the status code
     * @param contentType the content type
     * @param body        the body
     */
    public HttpResponse {
        body = body == null ? new byte[0] : body.clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }

    /** @return the body as UTF-8 text */
    public String bodyText() {
        return new String(body, StandardCharsets.UTF_8);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof HttpResponse that && status == that.status
                && Objects.equals(contentType, that.contentType) && Arrays.equals(body, that.body);
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, contentType, Arrays.hashCode(body));
    }

    @Override
    public String toString() {
        return "HttpResponse[status=" + status + ", contentType=" + contentType + ", bytes=" + body.length + "]";
    }
}
