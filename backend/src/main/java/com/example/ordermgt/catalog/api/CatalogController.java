package com.example.ordermgt.catalog.api;

import com.example.ordermgt.catalog.domain.CatalogRepository;
import com.example.ordermgt.shared.api.ApiResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/catalog")
public class CatalogController {

    private final CatalogRepository catalogRepository;

    @GetMapping("/items")
    public ApiResponse<List<CatalogItemResponse>> items() {
        List<CatalogItemResponse> items =
                catalogRepository.findAllActive().stream()
                        .map(i -> new CatalogItemResponse(i.sku(), i.name(), i.unitPrice()))
                        .toList();
        return ApiResponse.ok(items);
    }
}
