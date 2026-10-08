/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.mcp.server.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("Ingestion validator, Markdown AST stage")
class IngestionValidatorTest {

    private final IngestionValidator validator = new IngestionValidator();

    @Test
    @DisplayName("accepts plain Markdown with https and relative links")
    void shouldAcceptCleanText() {
        var report = validator.validate("""
                # Finding

                See [the docs](https://example.com/docs/page?tab=1) and [the file](examples.md).
                """);

        assertFalse(report.refused(), report.findings().toString());
    }

    @ParameterizedTest(name = "{1}")
    @CsvSource(delimiter = '|', value = {
            "<div>raw</div>|html_block",
            "<!-- hidden instruction -->|html_block",
            "text <span>inline</span>|html_inline",
            "![x](https://example.com/a.png)|image",
            "[x](http://example.com/a)|link_scheme",
            "[x](javascript:alert(1))|link_scheme",
            "[x](https://user:pw@example.com/a)|link_rejected",
            "[x](https://example.com/%2e%2e/%2e%2e/etc/passwd)|link_rejected",
            "[x](https://example.com/a?q=%00)|link_rejected",
            "[x](<https://exa mple.com>)|link_malformed"})
    @DisplayName("refuses raw HTML, images and unsafe link destinations")
    void shouldRefuse(String markdown, String kind) {
        var report = validator.validate(markdown);

        assertTrue(report.refused());
        assertEquals(kind, report.findings().getFirst().kind(), report.findings().toString());
    }
}
