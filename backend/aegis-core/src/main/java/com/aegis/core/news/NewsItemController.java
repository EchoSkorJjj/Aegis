package com.aegis.core.news;

import com.aegis.core.news.NewsItemDtos.NewsItemResponse;
import com.aegis.core.platform.common.ApiExceptions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only view of the security news ingestion has normalised. Written by connectors, never by a
 * user, and gated by the same Aegis domain read roles as the inventory, advisory and incident APIs.
 */
@RestController
@RequestMapping("/api/news")
@SecurityRequirement(name = "bearer-jwt")
public class NewsItemController {

    private static final String READ = "hasAnyRole('SERVICE_OWNER','ANALYST','COMPLIANCE_ADMIN')";

    private final NewsItemStore items;

    public NewsItemController(NewsItemStore items) {
        this.items = items;
    }

    @GetMapping
    @PreAuthorize(READ)
    @Operation(summary = "List normalised security news, most recently published first")
    public Page<NewsItemResponse> listNews(
            @Parameter(description = "Return only articles mentioning this CVE, e.g. CVE-2021-44228")
            @RequestParam(required = false) String cve,
            @PageableDefault(size = 20, sort = "publishedAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        Page<NewsItem> page = cve == null || cve.isBlank()
                ? items.findPage(pageable)
                : items.findPageByCveReference(cve.trim(), pageable);
        return page.map(NewsItemResponse::from);
    }

    @GetMapping("/{sourceId}/{externalId}")
    @PreAuthorize(READ)
    @Operation(summary = "Get one article by feed and feed identifier")
    public NewsItemResponse getNewsItem(@PathVariable String sourceId, @PathVariable String externalId) {
        return items.findByExternalId(sourceId, externalId)
                .map(NewsItemResponse::from)
                .orElseThrow(() -> new ApiExceptions.NotFound(
                        "News item " + externalId + " not found for source " + sourceId));
    }
}
