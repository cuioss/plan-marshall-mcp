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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("CiEndpoint")
class CiEndpointTest {

    @Test
    @DisplayName("normalizes the base path and resolves relative and absolute targets")
    void resolves() {
        var endpoint = CiEndpoint.of(URI.create("https://gitlab.example.com/api/v4"));

        assertEquals(URI.create("https://gitlab.example.com/api/v4/"), endpoint.baseUri());
        assertEquals(URI.create("https://gitlab.example.com/api/v4/projects/1"), endpoint.resolve("/projects/1"));
        assertEquals(URI.create("https://other.example.com/x"), endpoint.resolve("https://other.example.com/x"));
        assertFalse(endpoint.cleartextLoopback());
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "https://api.github.com/x, true",
            "https://API.GITHUB.COM:443/x, true",
            "https://api.github.com:444/x, false",
            "http://api.github.com/x, false",
            "https://uploads.github.com/x, false",
            "/relative, false"
    })
    @DisplayName("compares scheme, host and effective port")
    void sameOrigin(String uri, boolean same) {
        assertEquals(same, CiEndpoint.of(URI.create("https://api.github.com")).sameOrigin(URI.create(uri)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://api.github.com/", "ftp://localhost/", "http://10.0.0.1:8080/"})
    @DisplayName("refuses cleartext except on loopback")
    void httpsOnly(String uri) {
        var base = URI.create(uri);

        assertThrows(IllegalArgumentException.class, () -> CiEndpoint.of(base));
    }

    @Test
    @DisplayName("admits cleartext on loopback for local fakes")
    void loopback() {
        assertTrue(CiEndpoint.of(URI.create("http://127.0.0.1:9999/")).cleartextLoopback());
        assertTrue(CiEndpoint.of(URI.create("http://localhost/")).sameOrigin(URI.create("http://localhost:80/a")));
    }
}
