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
package de.cuioss.pm.mcp;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;

/**
 * Main entry point of the PM-MCP server.
 * <p>
 * The container image's {@code HEALTHCHECK} invokes the application itself with
 * {@value HealthProbe#PROBE_FLAG}, because the target image carries no shell and no {@code curl}.
 * The probe is routed in {@link #main(String[])}, before Quarkus starts, so it answers while the
 * application is still booting. All probe logic lives in {@link HealthProbe}.
 *
 * @since 0.1
 */
@QuarkusMain
public class PlanMarshallMcpApplication implements QuarkusApplication {

    /**
     * Application entry point and routing point for the container health probe.
     *
     * @param args the command line; handed to Quarkus unchanged unless it requests a probe
     */
    public static void main(String[] args) {
        if (HealthProbe.isProbe(args)) {
            System.exit(HealthProbe.probe());
        }
        Quarkus.run(PlanMarshallMcpApplication.class, args);
    }

    @Override
    public int run(String... args) {
        Quarkus.waitForExit();
        return 0;
    }
}
