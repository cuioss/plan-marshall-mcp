/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.mcp.spike;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Supplier;


import de.cuioss.pm.mcp.server.PmMcpLogMessages;
import de.cuioss.pm.mcp.server.security.IdentityAttributes;
import de.cuioss.pm.mcp.server.security.RequestIdentity;
import de.cuioss.pm.mcp.spike.SpikeEngine.Pull;
import de.cuioss.pm.mcp.spike.SpikeQueue.Caller;
import de.cuioss.pm.mcp.spike.SpikeScenario.Ack;
import de.cuioss.tools.logging.CuiLogger;
import io.quarkiverse.mcp.server.McpConnection;
import io.quarkiverse.mcp.server.McpTrafficListener;
import io.quarkiverse.mcp.server.RawMessage;
import io.quarkiverse.mcp.server.ResourceManager;
import io.quarkiverse.mcp.server.ResourceResponse;
import io.quarkiverse.mcp.server.ResourceTemplateManager;
import io.quarkiverse.mcp.server.TextResourceContents;
import io.quarkiverse.mcp.server.ToolManager;
import io.quarkiverse.mcp.server.ToolManager.ToolArguments;
import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Handler;
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
 * A caller that authenticated with a job token of {@link SpikeJobs} (the worker relay {@code pm-mcp serve --job})
 * is identified by its security identity: worker, generation and role come from the job, never from an
 * argument. Otherwise a caller is identified by the tool argument {@code worker} when it passes one, else by its
 * MCP connection. The argument is needed for hosts that call without a session: every request of such a host
 * arrives on a transient connection of its own, so the connection cannot bind a task to its worker.
 * <p>
 * The switches of the scenario add the tools of the supervised protocol: {@code pull_ack} and
 * {@code pull_task} (acknowledgement), {@code pull_consult}, the skill tools {@code pm_skills},
 * {@code pm_skill} and {@code pm_skill_file} with the resources {@code skill://pm/…}, and the driver's
 * {@code spike_state}, {@code spike_fence}, {@code spike_compacted} and {@code spike_skill_update}.
 */
@ApplicationScoped
public class SpikeTools implements McpTrafficListener {

    static final String PULL_WAIT = "pull_wait";
    static final String PULL_SUBMIT = "pull_submit";
    static final String PULL_INFO = "pull_info";
    static final String PULL_ESCALATE = "pull_escalate";
    static final String PULL_ACK = "pull_ack";
    static final String PULL_TASK = "pull_task";
    static final String PULL_CONSULT = "pull_consult";
    static final String PM_SKILLS = "pm_skills";
    static final String PM_SKILL = "pm_skill";
    static final String PM_SKILL_FILE = "pm_skill_file";
    static final String SPIKE_STATE = "spike_state";
    static final String SPIKE_FENCE = "spike_fence";
    static final String SPIKE_COMPACTED = "spike_compacted";
    static final String SPIKE_SKILL_UPDATE = "spike_skill_update";
    static final String WORKER = "worker";
    static final String GENERATION = "generation";
    static final String ROLE = "role";
    /** The client capability a host declares when it activates skills itself (SEP-2640). */
    static final String SKILLS_EXTENSION = "io.modelcontextprotocol/skills";
    private static final String WORKER_DESCRIPTION = "Your worker id, when you were given one";
    private static final String GENERATION_DESCRIPTION = "Set by the relay: the generation of the worker";
    private static final String ROLE_DESCRIPTION = "Set by the relay: the role of the worker";
    private static final String TASK_ID_DESCRIPTION = "The task_id of the task";

    private static final CuiLogger LOGGER = new CuiLogger(SpikeTools.class);
    private static final String DEFAULT_RUN_DIR = ".plan/temp/pull-spike/run";
    private static final String CALL = "call";
    private static final String URI = "uri";
    private static final String REASON = "reason";
    private static final String OUTCOME = "outcome";
    private static final String HELD_MS = "held_ms";
    private static final String WAIT_END = "wait_end";
    private static final String MARKDOWN = "text/markdown";
    private static final long NO_TIMER = -1;
    /** How often a held wait of the supervised protocol asks the engine again. */
    private static final long TICK_MILLIS = 250;

    private final ToolManager toolManager;
    private final ResourceManager resourceManager;
    private final ResourceTemplateManager templateManager;
    private final Vertx vertx;
    private final Map<String, McpConnection> connections = new ConcurrentHashMap<>();
    private final Set<String> skillHosts = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean toolAdded = new AtomicBoolean();
    private final SpikeScenario scenario;
    private final SpikeEventLog log;
    private final SpikeSkills skills;
    private final SpikeEngine engine;
    private final RequestIdentity identity;
    private final SpikeJobs jobs;

    /**
     * @param toolManager     the tool registry of the MCP server
     * @param resourceManager the resource registry of the MCP server
     * @param templateManager the resource-template registry of the MCP server
     * @param vertx           timers for the blocking wait
     * @param scenarioFile    the scenario file; the stub stays inactive without it
     * @param runDir          the directory of the event log
     * @param identity        the security identity of the current request
     * @param jobs            the job tokens of the harness drivers
     */
    SpikeTools(ToolManager toolManager, ResourceManager resourceManager, ResourceTemplateManager templateManager,
            Vertx vertx, @ConfigProperty(name = "pm.spike.scenario") Optional<String> scenarioFile,
            @ConfigProperty(name = "pm.spike.run-dir") Optional<String> runDir, RequestIdentity identity,
            SpikeJobs jobs) {
        this.identity = identity;
        this.jobs = jobs;
        this.toolManager = toolManager;
        this.resourceManager = resourceManager;
        this.templateManager = templateManager;
        this.vertx = vertx;
        if (scenarioFile.isEmpty()) {
            scenario = null;
            log = null;
            skills = null;
            engine = null;
            return;
        }
        try {
            scenario = SpikeScenario.load(Path.of(scenarioFile.get()));
            var skillsDir = scenario.protocol().skillsDir();
            skills = skillsDir == null ? null : SpikeSkills.load(Path.of(skillsDir));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var directory = runDir.orElse(DEFAULT_RUN_DIR);
        log = new SpikeEventLog(Path.of(directory));
        engine = new SpikeEngine(scenario, log, log::monoMillis, this::alive, skills);
        LOGGER.info(PmMcpLogMessages.INFO.SPIKE_ACTIVE, scenarioFile.get(), directory);
    }

    void onStart(@Observes StartupEvent event) {
        if (engine == null) {
            return;
        }
        var protocol = scenario.protocol();
        identified(toolManager.newTool(PULL_WAIT)
                .setDescription(protocol.supervised()
                        ? "Waits for the next piece of work. Returns status wait_again, task, consultation_answer or end."
                        : "Waits for the next piece of work. Returns status wait_again, task or done.")
                .addArgument(WORKER, WORKER_DESCRIPTION, false, String.class))
                .setAsyncHandler(this::pullWait)
                .register();
        var submit = toolManager.newTool(PULL_SUBMIT)
                .setDescription("Submits the decision for a task received from pull_wait.")
                .addArgument(SpikeEngine.TASK_ID, TASK_ID_DESCRIPTION, true, String.class)
                .addArgument("decision", "The decision taken", true, String.class)
                .addArgument("rationale", "One sentence giving the reason", false, String.class)
                .addArgument(WORKER, WORKER_DESCRIPTION, false, String.class);
        if (protocol.consult()) {
            submit.addArgument(SpikeEngine.CONSULTATION_ID, "The consultation the decision rests on, when there was one",
                    false, String.class);
        }
        identified(submit).setHandler(this::pullSubmit).register();
        toolManager.newTool(PULL_INFO)
                .setDescription("Returns general information about the server.")
                .setHandler(args -> other("info_called", args, "{\"info\":\"nothing to report\"}"))
                .register();
        if (protocol.supervised()) {
            registerProtocol();
        }
        if (skills != null) {
            registerSkills();
        }
        if (protocol.control()) {
            registerControl();
        }
        log.log("run_start", null, new JsonObject().put("steps", scenario.steps().size())
                .put("wait_ms", scenario.waitMillis()).put("progress_ms", scenario.progressMillis())
                .put("lease_ms", scenario.leaseMillis()).put("slots", scenario.slots()));
    }

    private void registerProtocol() {
        var protocol = scenario.protocol();
        if (protocol.ack() == Ack.EXPLICIT) {
            identified(toolManager.newTool(PULL_ACK)
                    .setDescription("Confirms a task received from pull_wait before working on it.")
                    .addArgument(SpikeEngine.TASK_ID, TASK_ID_DESCRIPTION, true, String.class)
                    .addArgument(WORKER, WORKER_DESCRIPTION, false, String.class))
                    .setHandler(args -> withTask(args, (caller, taskId) -> {
                        var result = engine.acknowledge(caller, taskId);
                        return new SpikeEngine.Reply(result.accepted(), new JsonObject()
                                .put("accepted", result.accepted()).put(REASON, result.reason()));
                    }))
                    .register();
        }
        if (protocol.ack() == Ack.IMPLICIT) {
            identified(toolManager.newTool(PULL_TASK)
                    .setDescription("Returns the facts and options of a task received from pull_wait.")
                    .addArgument(SpikeEngine.TASK_ID, TASK_ID_DESCRIPTION, true, String.class)
                    .addArgument(WORKER, WORKER_DESCRIPTION, false, String.class))
                    .setHandler(args -> withTask(args, engine::task))
                    .register();
        }
        if (protocol.consult()) {
            identified(toolManager.newTool(PULL_CONSULT)
                    .setDescription("Asks another role about a task; the answer arrives through pull_wait.")
                    .addArgument(SpikeEngine.TASK_ID, TASK_ID_DESCRIPTION, true, String.class)
                    .addArgument("question", "The question to the other role", true, String.class)
                    .addArgument(WORKER, WORKER_DESCRIPTION, false, String.class))
                    .setHandler(args -> withTask(args, (caller, taskId) -> engine.consult(caller, taskId,
                            String.valueOf(args.args().get("question")))))
                    .register();
        }
    }

    private void registerSkills() {
        toolManager.newTool(PM_SKILLS)
                .setDescription("Lists the skills of plan-marshall with their files, digests and sizes.")
                .setHandler(_ -> ToolResponse.success(new JsonObject().put("skills", skills.manifests()).encode()))
                .register();
        for (var name : new String[]{PM_SKILL, PM_SKILL_FILE}) {
            toolManager.newTool(name)
                    .setDescription(PM_SKILL.equals(name) ? "Returns a skill by the URI of its SKILL.md."
                            : "Returns a supporting file of a skill by its URI.")
                    .addArgument(URI, "A skill://pm/ URI", true, String.class)
                    .addArgument(WORKER, WORKER_DESCRIPTION, false, String.class)
                    .addArgument(GENERATION, GENERATION_DESCRIPTION, false, Integer.class)
                    .setHandler(this::skillRead)
                    .register();
        }
        for (var file : skills.files()) {
            if (file.uri().endsWith("/SKILL.md")) {
                resourceManager.newResource(file.uri().substring(SpikeSkills.SCHEME.length()))
                        .setUri(file.uri()).setDescription("Skill of plan-marshall").setMimeType(MARKDOWN)
                        .setHandler(args -> resource(args.requestUri().value(), args.connection()))
                        .register();
            }
        }
        templateManager.newResourceTemplate("pm-skill-file")
                .setUriTemplate(SpikeSkills.SCHEME + "{group}/{skill}/{file}")
                .setDescription("Supporting file of a skill of plan-marshall").setMimeType(MARKDOWN)
                .setHandler(args -> resource(args.requestUri().value(), args.connection()))
                .register();
    }

    private void registerControl() {
        toolManager.newTool(SPIKE_STATE)
                .setDescription("Driver only: the queue, the leases and the idle wake-ups per worker.")
                .setHandler(_ -> ToolResponse.success(engine.state().encode()))
                .register();
        toolManager.newTool(SPIKE_FENCE)
                .setDescription("Driver only: declares a worker generation as replaced.")
                .addArgument(WORKER, "The worker", true, String.class)
                .addArgument(GENERATION, "The highest replaced generation", true, Integer.class)
                .setHandler(args -> ToolResponse.success(engine.fence(String.valueOf(args.args().get(WORKER)),
                        generation(args)).encode()))
                .register();
        toolManager.newTool(SPIKE_COMPACTED)
                .setDescription("Driver only: reports that the context of a worker was compacted.")
                .addArgument(WORKER, "The worker", true, String.class)
                .setHandler(args -> {
                    var worker = String.valueOf(args.args().get(WORKER));
                    if (skills != null) {
                        skills.compacted(worker);
                    }
                    log.log("compacted", worker, new JsonObject());
                    return ToolResponse.success(new JsonObject().put(WORKER, worker).encode());
                })
                .register();
        if (skills != null) {
            toolManager.newTool(SPIKE_SKILL_UPDATE)
                    .setDescription("Driver only: replaces the content of a skill file.")
                    .addArgument(URI, "The skill:// URI", true, String.class)
                    .addArgument("content", "The new content", true, String.class)
                    .setHandler(args -> {
                        var file = skills.put(String.valueOf(args.args().get(URI)),
                                String.valueOf(args.args().get("content")));
                        log.log("skill_updated", null, new JsonObject().put(URI, file.uri())
                                .put("digest", file.digest()));
                        return ToolResponse.success(new JsonObject().put(URI, file.uri())
                                .put("digest", file.digest()).encode());
                    })
                    .register();
        }
    }

    /** Under the supervised protocol the relay adds the generation and the role of its worker to a call. */
    private ToolManager.ToolDefinition identified(ToolManager.ToolDefinition tool) {
        if (!scenario.protocol().supervised()) {
            return tool;
        }
        return tool.addArgument(GENERATION, GENERATION_DESCRIPTION, false, Integer.class)
                .addArgument(ROLE, ROLE_DESCRIPTION, false, String.class);
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
            var capabilities = params.getJsonObject("capabilities");
            fields.put("client_info", params.getJsonObject("clientInfo"))
                    .put("protocol_version", params.getString("protocolVersion"))
                    .put("capabilities", capabilities);
            if (declaresSkills(capabilities)) {
                skillHosts.add(connection.id());
                fields.put("skills_extension", true);
            }
        } else if (params != null && "notifications/cancelled".equals(method)) {
            fields.put("request_id", params.getValue("requestId")).put(REASON, params.getString(REASON));
        } else if (params != null && "resources/read".equals(method)) {
            fields.put(URI, params.getString(URI));
        }
        log.log("rx", connection.id(), fields);
    }

    private static boolean declaresSkills(JsonObject capabilities) {
        if (capabilities == null) {
            return false;
        }
        var extensions = capabilities.getValue("extensions");
        return capabilities.containsKey(SKILLS_EXTENSION)
                || extensions instanceof JsonObject declared && declared.containsKey(SKILLS_EXTENSION);
    }

    private Uni<ToolResponse> pullWait(ToolArguments args) {
        var caller = identify(args);
        var connection = caller.worker();
        var call = String.valueOf(args.requestId().value());
        if (scenario.protocol().supervised()) {
            return heldWait(args, caller, call);
        }
        var pull = engine.pull(connection);
        var status = pull.body().getString(SpikeEngine.STATUS);
        log.log("wait_start", connection, new JsonObject().put(CALL, call).put(SpikeEngine.STATUS, status)
                .put("block_ms", pull.millis()).put("progress_token", args.progress().token().isPresent())
                .put("mcp_connection", args.connection().id()));
        addToolWhenDue(connection);
        if (pull.millis() <= 0) {
            log.log(WAIT_END, connection, new JsonObject().put(CALL, call).put(OUTCOME, status));
            return Uni.createFrom().item(ToolResponse.success(pull.body().encode()));
        }
        return hold(args, connection, call, pull.millis(), false, () -> pull);
    }

    /** A wait of the supervised protocol: held until the engine has an answer for the caller. */
    private Uni<ToolResponse> heldWait(ToolArguments args, Caller caller, String call) {
        var byUri = skillHosts.contains(args.connection().id());
        var started = log.monoMillis();
        var first = engine.poll(caller, 0, byUri);
        log.log("wait_start", caller.worker(), new JsonObject().put(CALL, call)
                .put(SpikeEngine.STATUS, first == null ? "held" : first.body().getString(SpikeEngine.STATUS))
                .put("progress_token", args.progress().token().isPresent())
                .put("mcp_connection", args.connection().id()).put(GENERATION, caller.generation())
                .put(ROLE, caller.role()));
        if (first != null) {
            log.log(WAIT_END, caller.worker(), new JsonObject().put(CALL, call)
                    .put(OUTCOME, first.body().getString(SpikeEngine.STATUS)).put(GENERATION, caller.generation()));
            return Uni.createFrom().item(ToolResponse.success(first.body().encode()));
        }
        return hold(args, caller.worker(), call, TICK_MILLIS, true,
                () -> engine.poll(caller, log.monoMillis() - started, byUri));
    }

    /**
     * Holds a wait call: asks {@code answer} after {@code millis}, once or periodically, and completes the
     * call with the first answer; progress frames go out meanwhile when the caller sent a token.
     */
    private Uni<ToolResponse> hold(ToolArguments args, String connection, String call, long millis, boolean periodic,
            Supplier<Pull> answer) {
        var progress = args.progress().token().isPresent() && scenario.progressMillis() > 0;
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
                                .put(CALL, call).put("elapsed_s", elapsed).put(REASON, failure.getMessage())));
            }) : NO_TIMER;
            Handler<Long> tick = _ -> {
                if (finished.get()) {
                    return;
                }
                var pull = answer.get();
                if (pull != null && finished.compareAndSet(false, true)) {
                    log.log(WAIT_END, connection, new JsonObject().put(CALL, call)
                            .put(OUTCOME, pull.body().getString(SpikeEngine.STATUS))
                            .put(HELD_MS, log.monoMillis() - started));
                    emitter.complete(ToolResponse.success(pull.body().encode()));
                }
            };
            var timer = periodic ? vertx.setPeriodic(millis, tick) : vertx.setTimer(millis, tick);
            args.cancellation().onCancelled(reason -> {
                if (finished.compareAndSet(false, true)) {
                    log.log(WAIT_END, connection, new JsonObject().put(CALL, call).put(OUTCOME, "cancelled")
                            .put(HELD_MS, log.monoMillis() - started).put(REASON, reason.orElse(null)));
                    emitter.complete(ToolResponse.error("cancelled"));
                }
            });
            emitter.onTermination(() -> {
                vertx.cancelTimer(timer);
                if (progressTimer != NO_TIMER) {
                    vertx.cancelTimer(progressTimer);
                }
                if (finished.compareAndSet(false, true)) {
                    log.log(WAIT_END, connection, new JsonObject().put(CALL, call).put(OUTCOME, "aborted")
                            .put(HELD_MS, log.monoMillis() - started));
                }
            });
        });
    }

    private ToolResponse pullSubmit(ToolArguments args) {
        var caller = identify(args);
        var values = args.args();
        if (!(values.get(SpikeEngine.TASK_ID) instanceof String taskId)
                || !(values.get("decision") instanceof String decision)) {
            log.log("submit_refused", caller.worker(), new JsonObject().put(REASON, "missing_argument"));
            return ToolResponse.error("{\"accepted\":false,\"reason\":\"missing_argument\"}");
        }
        var rationale = (String) values.get("rationale");
        var submission = scenario.protocol().supervised()
                ? engine.submit(caller, taskId, decision, rationale,
                values.get(SpikeEngine.CONSULTATION_ID) instanceof String id ? id : null)
                : engine.submit(caller.worker(), taskId, decision, rationale);
        var body = new JsonObject().put("accepted", submission.accepted()).put(REASON, submission.reason());
        if (scenario.nextHints()) {
            body.put("next", "call pull_wait");
        }
        return submission.accepted() ? ToolResponse.success(body.encode()) : ToolResponse.error(body.encode());
    }

    private ToolResponse withTask(ToolArguments args,
            BiFunction<Caller, String, SpikeEngine.Reply> action) {
        var caller = identify(args);
        if (!(args.args().get(SpikeEngine.TASK_ID) instanceof String taskId)) {
            return ToolResponse.error("{\"accepted\":false,\"reason\":\"missing_argument\"}");
        }
        var reply = action.apply(caller, taskId);
        return reply.accepted() ? ToolResponse.success(reply.body().encode())
                : ToolResponse.error(reply.body().encode());
    }

    private ToolResponse skillRead(ToolArguments args) {
        var caller = identify(args);
        var uri = String.valueOf(args.args().get(URI));
        var file = skills.file(uri);
        log.log("skill_read", caller.worker(), new JsonObject().put(URI, uri).put("via", "tool")
                .put("found", file.isPresent()).put(GENERATION, caller.generation()));
        if (file.isEmpty()) {
            return ToolResponse.error(new JsonObject().put(REASON, "unknown_skill").put(URI, uri).encode());
        }
        skills.read(caller, file.get());
        return ToolResponse.success(file.get().content());
    }

    private ResourceResponse resource(String uri, McpConnection connection) {
        var file = skills.file(uri);
        log.log("skill_read", connection.id(), new JsonObject().put(URI, uri).put("via", "resource")
                .put("found", file.isPresent()));
        return new ResourceResponse(new TextResourceContents(uri, file.map(SpikeSkills.File::content).orElse(""),
                MARKDOWN));
    }

    private ToolResponse other(String event, ToolArguments args, String answer) {
        log.log(event, identify(args).worker(), new JsonObject()
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

    /**
     * Fences a worker generation for the driver's job revocation.
     *
     * @param worker     the worker
     * @param generation the highest replaced generation
     * @return the tasks given back
     */
    JsonObject fence(String worker, int generation) {
        return engine.fence(worker, generation);
    }

    private Caller identify(ToolArguments args) {
        var job = identity.current()
                .map(current -> current.attributes().get(IdentityAttributes.JOB_ID))
                .flatMap(jobId -> jobs.job(String.valueOf(jobId)));
        if (job.isPresent()) {
            var found = job.get();
            return new Caller(found.worker(), found.generation(), found.role());
        }
        var connection = args.connection();
        var worker = args.args().get(WORKER) instanceof String named && !named.isBlank() ? named : connection.id();
        if (!connection.isTransient()) {
            connections.put(worker, connection);
        }
        return new Caller(worker, generation(args), args.args().get(ROLE) instanceof String role ? role : null);
    }

    private static int generation(ToolArguments args) {
        return args.args().get(GENERATION) instanceof Number number ? number.intValue() : 0;
    }

    /** A worker without an observable connection is taken as alive: its tasks return by lease expiry. */
    private boolean alive(String worker) {
        var known = connections.get(worker);
        return known == null || known.status() != McpConnection.Status.CLOSED;
    }
}
