package com.forge.platform.common;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Forwards React Router client-side paths to index.html when frontend static assets
 * are bundled inside the unified Spring Boot deployment container.
 */
@Controller
public class SpaForwardController {

    @GetMapping({
        "/login",
        "/showcase",
        "/engineering",
        "/repositories",
        "/code-intel",
        "/reviews",
        "/reviews/{reviewId}",
        "/predictions",
        "/security",
        "/ci",
        "/analytics",
        "/audit",
        "/settings"
    })
    public String forwardSpaRoutes() {
        return "forward:/index.html";
    }
}
