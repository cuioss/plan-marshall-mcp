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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import de.cuioss.pm.api.http.HttpRequest;
import de.cuioss.pm.api.json.JsonTree;
import de.cuioss.pm.api.runtime.RuntimeClient;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Milestone 0 Part B verification verbs, not product: {@code spike keyring put|get|delete} against
 * the credential endpoints, and {@code spike events --count N}, which prints the arrival time of N
 * heartbeats of the runtime's event stream to show that Server-Sent Events arrive unbuffered.
 * Deleted at the Part B exit.
 */
@Command(name = "spike", description = "Milestone 0 verification verbs (not product).",
        subcommands = SpikeCommand.Keyring.class)
public final class SpikeCommand {

    /** Opens the runtime client of a spike verb. */
    @FunctionalInterface
    public interface ClientSource {
        /**
         * @return the runtime client, starting the runtime on demand
         * @throws IOException if it cannot be set up
         */
        RuntimeClient open() throws IOException;
    }

    private final ClientSource clients;

    @Spec
    CommandSpec spec;

    /**
     * @param clients the runtime client source
     */
    public SpikeCommand(ClientSource clients) {
        this.clients = clients;
    }

    @Command(name = "events", description = "Reads N heartbeats and prints the arrival time of each.")
    int events(@Option(names = "--count", defaultValue = "3", description = "Number of events.") int count)
            throws IOException {
        var out = spec.commandLine().getOut();
        var started = System.nanoTime();
        try (var exchange = clients.open().open(HttpRequest.get("/api/v1/events"))) {
            if (exchange.status() != 200 || !exchange.isEventStream()) {
                throw new IOException("events refused by the runtime (HTTP " + exchange.status() + ")");
            }
            var events = exchange.events();
            for (var i = 0; i < count; i++) {
                var event = events.next();
                if (event == null) {
                    break;
                }
                var elapsedMicros = (System.nanoTime() - started) / 1000;
                out.printf("%s %s +%d.%03d ms at %d%n", event.event(), event.data(), elapsedMicros / 1000,
                        elapsedMicros % 1000, System.currentTimeMillis());
                out.flush();
            }
        }
        return 0;
    }

    /** {@code spike keyring put|get|delete}: the credential endpoints of the runtime. */
    @Command(name = "keyring", description = "Credential store round trips (spike).")
    public static final class Keyring {

        private final ClientSource clients;

        @Spec
        CommandSpec spec;

        /**
         * @param clients the runtime client source
         */
        public Keyring(ClientSource clients) {
            this.clients = clients;
        }

        @Command(name = "put", description = "Stores a value.")
        int put(@Parameters(index = "0", paramLabel = "<key>") String key,
                @Parameters(index = "1", paramLabel = "<value>") String value) throws IOException {
            var body = new LinkedHashMap<String, Object>();
            body.put("value", value);
            return call(HttpRequest.json("PUT", path(key), JsonTree.write(body)), 204, _ -> "stored " + key);
        }

        @Command(name = "get", description = "Reads a value.")
        int get(@Parameters(index = "0", paramLabel = "<key>") String key) throws IOException {
            return call(HttpRequest.get(path(key)), 200, body -> {
                Map<String, Object> entry;
                try {
                    entry = JsonTree.asObject(JsonTree.parse(body));
                } catch (IOException e) {
                    entry = null;
                }
                return JsonTree.string(entry, "value") + " (store " + JsonTree.string(entry, "store") + ")";
            });
        }

        @Command(name = "delete", description = "Deletes a value.")
        int delete(@Parameters(index = "0", paramLabel = "<key>") String key) throws IOException {
            return call(new HttpRequest("DELETE", path(key), Map.of(), null), 204, _ -> "deleted " + key);
        }

        private int call(HttpRequest request, int expected, Function<byte[], String> render) throws IOException {
            var response = clients.open().send(request);
            var out = spec.commandLine().getOut();
            if (response.status() == 404) {
                out.println("not found");
                out.flush();
                return 1;
            }
            if (response.status() != expected) {
                throw new IOException("keyring refused by the runtime (HTTP " + response.status() + ")");
            }
            out.println(render.apply(response.body()));
            out.flush();
            return 0;
        }

        static String path(String key) {
            var encoded = new StringBuilder("/api/v1/credentials/");
            for (var b : key.getBytes(StandardCharsets.UTF_8)) {
                var c = (char) (b & 0xff);
                if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || "-._~".indexOf(c) >= 0) {
                    encoded.append(c);
                } else {
                    encoded.append('%').append(String.format("%02X", b & 0xff));
                }
            }
            return encoded.toString();
        }
    }
}
