/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.mcp.spike.keyring;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;


import com.fasterxml.jackson.core.JsonFactory;
import de.cuioss.pm.api.MachinePaths;
import de.cuioss.pm.mcp.server.credentials.CredentialAccount;
import de.cuioss.pm.mcp.server.credentials.SecretStoreException;
import de.cuioss.pm.mcp.server.credentials.SecretStoreSelector;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * At start, selects the keyring backend for the machine root {@code pm.spike.keyring.base} (a
 * non-default base, so the entries live under an instance-qualified service name), stores, reads,
 * replaces and deletes one entry, and writes the outcome as JSON to {@code pm.spike.keyring.result}.
 */
@ApplicationScoped
class KeyringVerification {

    @ConfigProperty(name = "pm.spike.keyring.result")
    Optional<String> result;

    @ConfigProperty(name = "pm.spike.keyring.base")
    Optional<String> base;

    void onStart(@Observes StartupEvent event) {
        if (result.isEmpty() || base.isEmpty()) {
            return;
        }
        var selection = SecretStoreSelector.select(new MachinePaths(Path.of(base.get()), MachinePaths.Os.current()));
        var store = selection.store();
        var account = CredentialAccount.global("pm-mcp-spike-verify");
        var secret = UUID.randomUUID().toString();
        long putNanos = 0;
        long getNanos = 0;
        long deleteNanos = 0;
        boolean roundTrip = false;
        String error = null;
        try {
            long start = System.nanoTime();
            store.put(account, secret);
            putNanos = System.nanoTime() - start;
            start = System.nanoTime();
            var read = store.get(account);
            getNanos = System.nanoTime() - start;
            store.put(account, secret + "-2");
            var replaced = store.get(account);
            start = System.nanoTime();
            boolean deleted = store.delete(account);
            deleteNanos = System.nanoTime() - start;
            roundTrip = read.equals(Optional.of(secret)) && replaced.equals(Optional.of(secret + "-2")) && deleted
                    && store.get(account).isEmpty();
        } catch (SecretStoreException e) {
            error = e.reason().code() + ": " + e.getMessage();
        }
        write(selection.store().name(), selection.service(), selection.fallbackReason().orElse(null), roundTrip,
                putNanos, getNanos, deleteNanos, error);
    }

    private void write(String store, String service, String fallback, boolean roundTrip, long put, long get,
            long delete, String error) {
        var json = new StringWriter();
        try (var generator = new JsonFactory().createGenerator(json)) {
            generator.writeStartObject();
            generator.writeStringField("store", store);
            generator.writeStringField("service", service);
            generator.writeStringField("fallback_reason", fallback);
            generator.writeBooleanField("round_trip", roundTrip);
            generator.writeNumberField("put_us", put / 1000);
            generator.writeNumberField("get_us", get / 1000);
            generator.writeNumberField("delete_us", delete / 1000);
            generator.writeStringField("error", error);
            generator.writeEndObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            Files.writeString(Path.of(result.orElseThrow()), json.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
