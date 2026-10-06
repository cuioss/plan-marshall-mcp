/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.provider.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;

import org.eclipse.jgit.transport.URIish;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("RemotePolicy")
class RemotePolicyTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "https://github.com/cuioss/x.git, true",
            "https://GITHUB.com:443/cuioss/x.git, true",
            "https://github.com:8443/cuioss/x.git, false",
            "https://gitlab.com/cuioss/x.git, false",
            "http://github.com/cuioss/x.git, false",
            "git@github.com:cuioss/x.git, false",
            "ssh://git@github.com/cuioss/x.git, false",
            "file:///tmp/x.git, false"
    })
    @DisplayName("admits only the https origin of the credential entry")
    void httpsOrigin(String remote, boolean permitted) throws Exception {
        var policy = RemotePolicy.httpsOrigin(URI.create("https://github.com"));

        assertEquals(permitted, policy.permits(new URIish(remote)));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "file:///tmp/x.git, true",
            "/tmp/x.git, true",
            "https://github.com/cuioss/x.git, false",
            "git@github.com:cuioss/x.git, false"
    })
    @DisplayName("admits local repositories only")
    void localOnly(String remote, boolean permitted) throws Exception {
        assertEquals(permitted, RemotePolicy.localOnly().permits(new URIish(remote)));
    }

    @Test
    @DisplayName("rejects a non-https origin")
    void rejectsOrigin() {
        var origin = URI.create("http://github.com");

        assertThrows(IllegalArgumentException.class, () -> RemotePolicy.httpsOrigin(origin));
    }
}
