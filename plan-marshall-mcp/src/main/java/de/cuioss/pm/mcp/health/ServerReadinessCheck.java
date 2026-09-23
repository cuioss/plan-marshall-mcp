/*
 * Copyright © 2026-present CUI-OpenSource-Software (info@cuioss.de)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.cuioss.pm.mcp.health;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/**
 * Readiness check of the PM-MCP server.
 * <p>
 * Reports ready once the application is started; later stages add the core daemon's state.
 *
 * @since 0.1
 */
@Readiness
@ApplicationScoped
public class ServerReadinessCheck implements HealthCheck {

    /** Name of the health check as reported by {@code /q/health}. */
    public static final String NAME = "plan-marshall-mcp";

    @Override
    public HealthCheckResponse call() {
        return HealthCheckResponse.up(NAME);
    }
}
