/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.provider.ci;

import java.util.List;

/**
 * The pages of a paginated read.
 *
 * @param pages    the bodies of the pages read, in order
 * @param complete {@code true} only if the last page had no {@code rel="next"} link; a page cap, a
 *                 failed page, or a next link outside the origin leaves the read incomplete, and an
 *                 incomplete read is never treated as the full set
 * @param last     the response of the last request made
 * @since 0.1
 */
public record PagedResult(List<String> pages, boolean complete, CiResponse last) {

    /**
     * @param pages    the pages
     * @param complete the completeness flag
     * @param last     the last response
     */
    public PagedResult {
        pages = List.copyOf(pages);
    }
}
