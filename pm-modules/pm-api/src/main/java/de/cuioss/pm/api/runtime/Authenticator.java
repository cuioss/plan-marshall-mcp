/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.api.runtime;

import java.io.IOException;
import java.util.Objects;

/**
 * The credential a client presents on every request: the runtime token of a local client, or the
 * job token of a worker relay.
 */
public interface Authenticator {

    /**
     * One credential header.
     *
     * @param header the header name
     * @param value  the header value, a secret
     */
    record Credential(String header, String value) {

        /**
         * @param header the header name
         * @param value  the header value
         */
        public Credential {
            Objects.requireNonNull(header, "header");
            Objects.requireNonNull(value, "value");
        }

        @Override
        public String toString() {
            return "Credential[" + header + "]";
        }
    }

    /**
     * @return the credential to send
     * @throws java.nio.file.NoSuchFileException if no runtime has written its token
     * @throws IOException                       if the credential cannot be read safely
     */
    Credential credential() throws IOException;

    /**
     * Re-reads the credential after a {@code 401} or a runtime start.
     *
     * @return {@code true} if a new attempt with a re-read credential is worth making
     * @throws IOException if the credential cannot be read safely
     */
    boolean renew() throws IOException;

    /**
     * The runtime token, read with the client check of {@link RuntimeTokenFile}.
     *
     * @param file the token file
     * @return the authenticator sending {@code Authorization: Bearer <runtime token>}
     */
    static Authenticator runtimeToken(RuntimeTokenFile file) {
        return new Authenticator() {
            private volatile String token;

            @Override
            public Credential credential() throws IOException {
                var current = token;
                if (current == null) {
                    current = file.read();
                    token = current;
                }
                return new Credential("Authorization", "Bearer " + current);
            }

            @Override
            public boolean renew() {
                token = null;
                return true;
            }
        };
    }

    /**
     * A job token from the worker's environment; it is never re-read.
     *
     * @param jobToken the job token
     * @return the authenticator sending {@code PM-MCP-Job-Token}
     */
    static Authenticator jobToken(String jobToken) {
        var credential = new Credential("PM-MCP-Job-Token", jobToken);
        return new Authenticator() {
            @Override
            public Credential credential() {
                return credential;
            }

            @Override
            public boolean renew() {
                return false;
            }
        };
    }
}
