/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.provider.ci;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("Json")
class JsonTest {

    @Test
    @DisplayName("round-trips a tree and navigates it")
    void roundTrip() {
        var tree = new LinkedHashMap<String, Object>();
        tree.put("s", "line1\nline2 \"q\"");
        tree.put("n", 3L);
        tree.put("i", 4);
        tree.put("d", new BigDecimal("1.5"));
        tree.put("b", true);
        tree.put("z", null);
        tree.put("l", Arrays.asList("a", null, Map.of("k", false)));
        tree.put("o", Duration.ofSeconds(1));

        String json = Json.write(tree);
        Object parsed = Json.parse(json);

        assertEquals("{\"s\":\"line1\\nline2 \\\"q\\\"\",\"n\":3,\"i\":4,\"d\":1.5,\"b\":true,\"z\":null,"
                + "\"l\":[\"a\",null,{\"k\":false}],\"o\":\"PT1S\"}", json);
        assertEquals(Optional.of("line1\nline2 \"q\""), Json.string(parsed, "s"));
        assertEquals(Optional.of("3"), Json.string(parsed, "n"));
        assertTrue(Json.bool(parsed, "b"));
        assertFalse(Json.bool(parsed, "missing"));
        assertEquals(3, Json.list(parsed, "l").size());
        assertNull(Json.list(parsed, "l").get(1));
        assertEquals(List.of(), Json.list(parsed, "s"));
        assertEquals(Optional.empty(), Json.at(parsed, "s", "deeper"));
        assertEquals(Optional.empty(), Json.at(parsed, "z"));
    }

    @Test
    @DisplayName("rejects text that is not JSON")
    void rejects() {
        assertThrows(Json.JsonFormatException.class, () -> Json.parse(""));
        assertThrows(Json.JsonFormatException.class, () -> Json.parse("{\"a\":"));
        assertNull(Json.parseOrNull("<html>"));
    }

    @Test
    @DisplayName("maps failed responses to contract outcomes")
    void mapsOutcomes() {
        assertEquals(CiResult.Outcome.AUTH_FAILED, failed(401, "").outcome());
        assertEquals(CiResult.Outcome.PERMISSION_DENIED, failed(403, "").outcome());
        assertEquals(CiResult.Outcome.NOT_FOUND, failed(404, "").outcome());
        assertEquals(CiResult.Outcome.REJECTED, failed(409, "{\"message\":\"SHA does not match\"}").outcome());
        assertEquals("HTTP 409: SHA does not match", failed(409, "{\"message\":\"SHA does not match\"}").detail());
        assertEquals(CiResult.Outcome.FAILED, failed(500, "oops").outcome());
        assertEquals(CiResult.Outcome.FAILED, failed(500, "{\"message\":\"500 Internal Server Error\"}").outcome());
        assertEquals(CiResult.Outcome.FAILED,
                CiResult.failed(CiResponse.failure(CiResponse.Outcome.REFUSED, "outside the origin")).outcome());
        assertEquals(CiResult.Outcome.FAILED,
                CiResult.failed(CiResponse.failure(CiResponse.Outcome.TRANSPORT_FAILED, "IOException")).outcome());
        var limited = CiResult.failed(new CiResponse(CiResponse.Outcome.RATE_LIMITED, 429, "", Optional.empty(),
                Optional.empty(), Optional.of(Instant.EPOCH), "rate limited"));
        assertEquals(CiResult.Outcome.RATE_LIMITED, limited.outcome());
        assertEquals(Optional.of(Instant.EPOCH), limited.resetAt());
        assertFalse(limited.isOk());
        assertTrue(CiResult.ok("v", false).isOk());
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(delimiter = '|', value = {
            "400|{\"message\":\"Merge trains are not enabled for this project\"}|REJECTED|HTTP 400: Merge trains are not enabled for this project",
            "400|{\"error\":\"sha is missing\"}|REJECTED|HTTP 400: sha is missing",
            "409|{\"message\":\"SHA does not match\"}|REJECTED|HTTP 409: SHA does not match",
            "409|''|REJECTED|HTTP 409",
            "422|{\"message\":\"Validation Failed\"}|REJECTED|HTTP 422: Validation Failed",
            "405|{\"message\":\"Method Not Allowed\"}|REJECTED|HTTP 405: Method Not Allowed",
            "410|{\"message\":\"Gone\"}|REJECTED|HTTP 410: Gone",
            "400|''|FAILED|HTTP 400",
            "400|<html>bad gateway page</html>|FAILED|HTTP 400",
            "400|{\"message\":\" \"}|FAILED|HTTP 400",
            "400|{\"message\":null}|FAILED|HTTP 400",
            "401|{\"message\":\"Bad credentials\"}|AUTH_FAILED|HTTP 401: Bad credentials",
            "403|{\"message\":\"Resource not accessible by integration\"}|PERMISSION_DENIED|HTTP 403: Resource not accessible by integration",
            "404|{\"message\":\"Not Found\"}|NOT_FOUND|HTTP 404: Not Found",
            "502|{\"message\":\"Bad Gateway\"}|FAILED|HTTP 502: Bad Gateway",
            "302|{\"message\":\"moved\"}|FAILED|HTTP 302: moved"
    })
    @DisplayName("keeps a provider refusal (REJECTED) apart from a credential refusal and from a failure")
    void separatesRefusals(int status, String body, CiResult.Outcome outcome, String detail) {
        var result = failed(status, body);

        assertEquals(outcome, result.outcome());
        assertEquals(detail, result.detail());
        assertTrue(result.value().isEmpty());
    }

    private static CiResult<Object> failed(int status, String body) {
        return CiResult.failed(new CiResponse(CiResponse.Outcome.HTTP_ERROR, status, body, Optional.empty(),
                Optional.empty(), Optional.empty(), "HTTP " + status));
    }
}
