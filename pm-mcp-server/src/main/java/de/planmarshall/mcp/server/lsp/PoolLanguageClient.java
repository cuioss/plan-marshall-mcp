/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.mcp.server.lsp;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;


import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.services.LanguageClient;

/**
 * The client side the pool exposes to a language server: published diagnostics are cached per
 * document (the {@code source: published} fallback of {@code pm_lsp diagnostics}); messages,
 * telemetry and message requests are acknowledged without user interaction.
 * <p>
 * LSP4J dispatches incoming requests to these methods reflectively, hence the registration for
 * native reflection.
 *
 * @since 0.1
 */
@RegisterForReflection(methods = true)
final class PoolLanguageClient implements LanguageClient {

    private final Map<String, List<Diagnostic>> diagnostics = new ConcurrentHashMap<>();

    @Override
    public void telemetryEvent(Object object) {
        // telemetry is not consumed
    }

    @Override
    public void publishDiagnostics(PublishDiagnosticsParams params) {
        diagnostics.put(params.getUri(), List.copyOf(params.getDiagnostics()));
    }

    @Override
    public void showMessage(MessageParams params) {
        // messages are not shown to anyone
    }

    @Override
    public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams params) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void logMessage(MessageParams message) {
        // server log lines are not kept
    }

    /**
     * @param uri the document URI
     * @return the diagnostics last published for it
     */
    List<Diagnostic> publishedDiagnostics(String uri) {
        return diagnostics.getOrDefault(uri, List.of());
    }
}
