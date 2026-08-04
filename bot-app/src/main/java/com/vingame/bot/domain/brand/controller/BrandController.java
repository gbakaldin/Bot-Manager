package com.vingame.bot.domain.brand.controller;

import com.vingame.bot.domain.brand.dto.BrandProductsResponse;
import com.vingame.bot.domain.brand.service.BrandService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("api/v1/brand")
public class BrandController {

    private final BrandService service;

    @Autowired
    public BrandController(BrandService service) {
        this.service = service;
    }

    @Operation(
            summary = "Get brand to products mapping",
            description = "Returns a map of all brand codes to their associated product codes")
    @GetMapping("/products")
    public ResponseEntity<BrandProductsResponse> getBrandProducts() {
        return ResponseEntity.ok(service.getBrandProducts());
    }
}
