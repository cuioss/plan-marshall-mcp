/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.operator.spike;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import de.cuioss.pm.api.runtime.RuntimeClient;
import de.cuioss.pm.api.runtime.RuntimeTokenFile;
import de.cuioss.pm.api.testsupport.RuntimeFixture;

import picocli.CommandLine;

@DisplayName("spike verbs")
@EnabledOnOs({OS.MAC, OS.LINUX})
class SpikeCommandTest {

    private RuntimeFixture fixture;
    private StringWriter out;

    @BeforeEach
    void setUp() throws IOException {
        fixture = RuntimeFixture.create();
        out = new StringWriter();
    }

    @AfterEach
    void tearDown() throws IOException {
        fixture.close();
    }

    private int run(String... args) {
        SpikeCommand.ClientSource clients = () -> {
            var uid = (Integer) Files.getAttribute(fixture.paths().runtimeToken(), "unix:uid", LinkOption.NOFOLLOW_LINKS);
            return RuntimeClient.local(fixture.paths(), new RuntimeTokenFile(fixture.paths(), uid), null, Map.of());
        };
        var commandLine = new CommandLine(new SpikeCommand(clients), new CommandLine.IFactory() {
            @Override
            public <K> K create(Class<K> type) throws Exception {
                return type == SpikeCommand.Keyring.class ? type.cast(new SpikeCommand.Keyring(clients))
                        : CommandLine.defaultFactory().create(type);
            }
        });
        commandLine.setOut(new PrintWriter(out));
        return commandLine.execute(args);
    }

    @Test
    @DisplayName("keyring put, get and delete use the credential endpoints")
    void keyring() throws Exception {
        var server = fixture.start((request, response) -> {
            switch (request.method()) {
                case "PUT", "DELETE" -> response.send(204, null, "");
                default -> response.json(200, "{\"value\":\"s3cret\",\"store\":\"keychain\"}");
            }
        });

        assertEquals(0, run("keyring", "put", "a b/c", "s3cret"));
        assertEquals(0, run("keyring", "get", "a b/c"));
        assertEquals(0, run("keyring", "delete", "a b/c"));

        var put = server.awaitRequest(Duration.ofSeconds(1));
        assertEquals("/api/v1/credentials/a%20b%2Fc", put.target());
        assertEquals("{\"value\":\"s3cret\"}", put.bodyText());
        assertEquals("GET", server.awaitRequest(Duration.ofSeconds(1)).method());
        assertEquals("DELETE", server.awaitRequest(Duration.ofSeconds(1)).method());
        assertTrue(out.toString().contains("s3cret (store keychain)"));
    }

    @Test
    @DisplayName("keyring get reports a missing entry and an unexpected status")
    void missing() throws Exception {
        fixture.start((request, response) -> response.send("GET".equals(request.method()) ? 404 : 500, null, ""));

        assertEquals(1, run("keyring", "get", "x"));
        assertTrue(out.toString().contains("not found"));
        assertEquals(1, run("keyring", "delete", "x"));
    }

    @Test
    @DisplayName("events prints the arrival of each heartbeat")
    void events() throws Exception {
        fixture.start((request, response) -> {
            try (var events = response.events(200)) {
                for (var seq = 0; seq < 3; seq++) {
                    events.send("heartbeat", "{\"seq\":" + seq + "}");
                }
            }
        });

        assertEquals(0, run("events", "--count", "2"));

        var lines = out.toString().strip().split("\n");
        assertEquals(2, lines.length);
        assertTrue(lines[0].startsWith("heartbeat {\"seq\":0} +"));
        assertTrue(lines[1].startsWith("heartbeat {\"seq\":1} +"));
    }

    @Test
    @DisplayName("events fails on a non-stream answer")
    void eventsRefused() throws Exception {
        fixture.start((request, response) -> response.json(200, "{}"));

        assertEquals(1, run("events"));
    }
}
