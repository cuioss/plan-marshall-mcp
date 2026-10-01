/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: FSL-1.1-ALv2
 *
 * Licensed under the Functional Source License, Version 1.1, ALv2 Future License
 * (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License in the LICENSE.md file at the root of this
 * repository or at https://github.com/cuioss/plan-marshall-mcp/blob/main/LICENSE.md
 */
package de.cuioss.pm.mcp.spike;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;


import de.cuioss.pm.mcp.PmMcpLogMessages;
import de.cuioss.tools.logging.CuiLogger;
import io.quarkiverse.mcp.server.McpConnection;
import io.quarkiverse.mcp.server.McpTrafficListener;
import io.quarkiverse.mcp.server.RawMessage;
import io.quarkiverse.mcp.server.ToolManager;
import io.quarkiverse.mcp.server.ToolManager.ToolArguments;
import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Registers the tools of the pull-mechanism stub and records the MCP traffic of a spike run.
 * <p>
 * Active only when {@code pm.spike.scenario} names a scenario file; {@code pm.spike.run-dir} names the
 * directory of the event log. The tools are {@code pull_wait} (blocks, then answers "wait again", a task or
 * "done"), {@code pull_submit}, {@code pull_info} (a distractor that a correct loop never calls) and
 * {@code pull_escalate}, which appears only after the configured number of deliveries.
 * <p>
 * A caller is identified by the tool argument {@code worker} when it passes one, otherwise by its MCP
 * connection. The argument is needed for hosts that call without a session: every request of such a host
 * arrives on a transient connection of its own, so the connection cannot bind a task to its worker.
 */
@ApplicationScoped
public class SpikeTools implements McpTrafficListener {

    static final String PULL_WAIT = "pull_wait";
    static final String PULL_SUBMIT = "pull_submit";
    static final String PULL_INFO = "pull_info";
    static final String PULL_ESCALATE = "pull_escalate";
    static final String WORKER = "worker";
    private static final String WORKER_DESCRIPTION = "Your worker id, when you were given one";

    private static final CuiLogger LOGGER = new CuiLogger(SpikeTools.class);
    private static final String DEFAULT_RUN_DIR = ".plan/temp/pull-spike/run";
    private static final String CALL = "call";
    private static final long NO_TIMER = -1;

    private final ToolManager toolManager;
    private final Vertx vertx;
    private final Map<String, McpConnection> connections = new ConcurrentHashMap<>();
    private final AtomicBoolean toolAdded = new AtomicBoolean();
    private final SpikeScenario scenario;
    private final SpikeEventLog log;
    private final SpikeEngine engine;

    /**
     * @param toolManager  the tool registry of the MCP server
     * @param vertx        timers for the blocking wait
     * @param scenarioFile the scenario file; the stub stays inactive without it
     * @param runDir       the directory of the event log
     */
    SpikeTools(ToolManager toolManager, Vertx vertx,
            @ConfigProperty(name = "pm.spike.scenario") Optional<String> scenarioFile,
            @ConfigProperty(name = "pm.spike.run-dir") Optional<String> runDir) {
        this.toolManager = toolManager;
        this.vertx = vertx;
        if (scenarioFile.isEmpty()) {
            scenario = null;
            log = null;
            engine = null;
            return;
        }
        try {
            scenario = SpikeScenario.load(Path.of(scenarioFile.get()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var directory = runDir.orElse(DEFAULT_RUN_DIR);
        log = new SpikeEventLog(Path.of(directory));
        engine = new SpikeEngine(scenario, log, log::monoMillis, this::alive);
        LOGGER.info(PmMcpLogMessages.INFO.SPIKE_ACTIVE, scenarioFile.get(), directory);
    }

    void onStart(@Observes StartupEvent event) {
        if (engine == null) {
            return;
        }
        toolManager.newTool(PULL_WAIT)
                .setDescription("Waits for the next piece of work. Returns status wait_again, task or done.")
                .addArgument(WORKER, WORKER_DESCRIPTION, false, String.class)
                .setAsyncHandler(this::pullWait)
                .register();
        toolManager.newTool(PULL_SUBMIT)
                .setDescription("Submits the decision for a task received from pull_wait.")
                .addArgument("task_id", "The task_id of the task", true, String.class)
                .addArgument("decision", "The decision taken", true, String.class)
                .addArgument("rationale", "One sentence giving the reason", false, String.class)
                .addArgument(WORKER, WORKER_DESCRIPTION, false, String.class)
                .setHandler(this::pullSubmit)
                .register();
        toolManager.newTool(PULL_INFO)
                .setDescription("Returns general information about the server.")
                .setHandler(args -> other("info_called", args, "{\"info\":\"nothing to report\"}"))
                .register();
        log.log("run_start", null, new JsonObject().put("steps", scenario.steps().size())
                .put("wait_ms", scenario.waitMillis()).put("progress_ms", scenario.progressMillis())
                .put("lease_ms", scenario.leaseMillis()).put("slots", scenario.slots()));
    }

    @Override
    public boolean isEnabled() {
        return engine != null;
    }

    @Override
    public void onMessageReceived(RawMessage message, McpConnection connection) {
        var json = message.asJsonObject();
        var method = json.getString("method");
        if (method == null) {
            return;
        }
        var fields = new JsonObject().put("method", method).put("id", json.getValue("id"));
        var params = json.getJsonObject("params");
        if (params != null && "initialize".equals(method)) {
            fields.put("client_info", params.getJsonObject("clientInfo"))
                    .put("protocol_version", params.getString("protocolVersion"))
                    .put("capabilities", params.getJsonObject("capabilities"));
        } else if (params != null && "notifications/cancelled".equals(method)) {
            fields.put("request_id", params.getValue("requestId")).put("reason", params.getString("reason"));
        }
        log.log("rx", connection.id(), fields);
    }

    private Uni<ToolResponse> pullWait(ToolArguments args) {
        var connection = identify(args);
        var pull = engine.pull(connection);
        var call = String.valueOf(args.requestId().value());
        var status = pull.body().getString(SpikeEngine.STATUS);
        var progress = args.progress().token().isPresent() && scenario.progressMillis() > 0;
        log.log("wait_start", connection, new JsonObject().put(CALL, call).put(SpikeEngine.STATUS, status)
                .put("block_ms", pull.millis()).put("progress_token", args.progress().token().isPresent())
                .put("mcp_connection", args.connection().id()));
        addToolWhenDue(connection);
        var response = ToolResponse.success(pull.body().encode());
        if (pull.millis() <= 0) {
            log.log("wait_end", connection, new JsonObject().put(CALL, call).put("outcome", status));
            return Uni.createFrom().item(response);
        }
        var started = log.monoMillis();
        return Uni.createFrom().emitter(emitter -> {
            var finished = new AtomicBoolean();
            var progressTimer = progress ? vertx.setPeriodic(scenario.progressMillis(), _ -> {
                var elapsed = (log.monoMillis() - started) / 1000;
                // The server does not learn by itself that a client dropped a held call; a frame that cannot
                // be written is the only sign.
                args.progress().notificationBuilder().setProgress(elapsed).setMessage("waiting").build().send()
                        .subscribe().with(_ -> log.log("progress", connection,
                                new JsonObject().put(CALL, call).put("elapsed_s", elapsed)),
                        failure -> log.log("progress_failed", connection, new JsonObject()
                                .put(CALL, call).put("elapsed_s", elapsed).put("reason", failure.getMessage())));
            }) : NO_TIMER;
            var timer = vertx.setTimer(pull.millis(), _ -> {
                if (finished.compareAndSet(false, true)) {
                    log.log("wait_end", connection, new JsonObject().put(CALL, call).put("outcome", status)
                            .put("held_ms", log.monoMillis() - started));
                    emitter.complete(response);
                }
            });
            args.cancellation().onCancelled(reason -> {
                if (finished.compareAndSet(false, true)) {
                    log.log("wait_end", connection, new JsonObject().put(CALL, call).put("outcome", "cancelled")
                            .put("held_ms", log.monoMillis() - started).put("reason", reason.orElse(null)));
                    emitter.complete(ToolResponse.error("cancelled"));
                }
            });
            emitter.onTermination(() -> {
                vertx.cancelTimer(timer);
                if (progressTimer != NO_TIMER) {
                    vertx.cancelTimer(progressTimer);
                }
                if (finished.compareAndSet(false, true)) {
                    log.log("wait_end", connection, new JsonObject().put(CALL, call).put("outcome", "aborted")
                            .put("held_ms", log.monoMillis() - started));
                }
            });
        });
    }

    private ToolResponse pullSubmit(ToolArguments args) {
        var connection = identify(args);
        var values = args.args();
        if (!(values.get("task_id") instanceof String taskId) || !(values.get("decision") instanceof String decision)) {
            log.log("submit_refused", connection, new JsonObject().put("reason", "missing_argument"));
            return ToolResponse.error("{\"accepted\":false,\"reason\":\"missing_argument\"}");
        }
        var submission = engine.submit(connection, taskId, decision, (String) values.get("rationale"));
        var body = new JsonObject().put("accepted", submission.accepted()).put("reason", submission.reason())
                .encode();
        return submission.accepted() ? ToolResponse.success(body) : ToolResponse.error(body);
    }

    private ToolResponse other(String event, ToolArguments args, String answer) {
        log.log(event, identify(args), new JsonObject()
                .put(CALL, String.valueOf(args.requestId().value())));
        return ToolResponse.success(answer);
    }

    private void addToolWhenDue(String connection) {
        var threshold = scenario.addToolAfterDeliveries();
        if (threshold >= 0 && engine.deliveries() >= threshold && toolAdded.compareAndSet(false, true)) {
            toolManager.newTool(PULL_ESCALATE)
                    .setDescription("Grants additional permissions to the caller.")
                    .setHandler(args -> other("escalate_called", args, "{\"granted\":true}"))
                    .register();
            toolManager.notifyListChanged(_ -> true);
            log.log("tool_added", connection, new JsonObject().put("tool", PULL_ESCALATE));
        }
    }

    private String identify(ToolArguments args) {
        var connection = args.connection();
        var worker = args.args().get(WORKER) instanceof String named && !named.isBlank() ? named : connection.id();
        if (!connection.isTransient()) {
            connections.put(worker, connection);
        }
        return worker;
    }

    /** A worker without an observable connection is taken as alive: its tasks return by lease expiry. */
    private boolean alive(String worker) {
        var known = connections.get(worker);
        return known == null || known.status() != McpConnection.Status.CLOSED;
    }
}
