package ru.petstore.catalog.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.petstore.catalog.domain.Product;
import ru.petstore.catalog.repository.*;
import ru.petstore.catalog.web.dto.*;
import ru.petstore.common.reference.ReferenceDataService;
import ru.petstore.common.reference.ReferenceItem;
import ru.petstore.common.web.ResourceNotFoundException;

import java.util.*;

@Service
@Transactional(readOnly = true)
public class ProductService {

    private static final SortedSet<String> SORTABLE = Collections.unmodifiableSortedSet(new TreeSet<>(
            List.of("sku", "name", "price", "active", "createdAt", "updatedAt")));

    private static final String SKU_INDEX = "uq_product_sku";

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final SpeciesRepository speciesRepository;
    private final BrandRepository brandRepository;
    private final ReferenceDataService referenceDataService;

    public ProductService(ProductRepository productRepository,
                          CategoryRepository categoryRepository,
                          SpeciesRepository speciesRepository,
                          BrandRepository brandRepository,
                          ReferenceDataService referenceDataService) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.speciesRepository = speciesRepository;
        this.brandRepository = brandRepository;
        this.referenceDataService = referenceDataService;
    }

    public PageResponse<ProductResponse> search(ProductFilterRequest filter, Pageable pageable) {
        checkSortable(pageable.getSort());

        Specification<Product> spec = Specification.allOf(
                ProductSpecifications.referenceIs("category",
                        referenceDataService.getIdOrNull(ReferenceType.CATEGORY, filter.category())),
                ProductSpecifications.referenceIs("species",
                        referenceDataService.getIdOrNull(ReferenceType.SPECIES, filter.species())),
                ProductSpecifications.referenceIs("brand",
                        referenceDataService.getIdOrNull(ReferenceType.BRAND, filter.brand())),
                ProductSpecifications.activeIs(filter.active()));

        return PageResponse.of(productRepository.findAll(spec, pageable), ProductResponse::of);
    }

    public ProductResponse get(UUID id) {
        return productRepository.findById(id)
                .map(ProductResponse::of)
                .orElseThrow(() -> ResourceNotFoundException.of("Product", id));
    }

    public List<ProductSummary> getProductSummaries(Collection<UUID> ids) {
        return ids.isEmpty() ? List.of() : productRepository.findAllByIdIn(ids);
    }

    @Transactional
    public ProductResponse create(ProductRequest request) {
        if (productRepository.existsBySku(request.sku())) {
            throw duplicateSku(request.sku());
        }
        return applyAndSave(request, new Product(), true);
    }

    @Transactional
    public ProductResponse update(UUID id, ProductRequest request) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Product", id));

        if (!request.sku().equals(product.getSku()) && productRepository.existsBySku(request.sku())) {
            throw duplicateSku(request.sku());
        }

        return applyAndSave(request, product, product.isActive());
    }

    private ProductResponse applyAndSave(ProductRequest request, Product product, boolean activeWhenAbsent) {
        ReferenceItem categoryRef = referenceDataService.getRequired(ReferenceType.CATEGORY, request.categoryCode());
        ReferenceItem speciesRef = referenceDataService.getRequired(ReferenceType.SPECIES, request.speciesCode());
        ReferenceItem brandRef = referenceDataService.getRequired(ReferenceType.BRAND, request.brandCode());

        product.setSku(request.sku());
        product.setName(request.name());
        product.setDescription(request.description());
        product.setPrice(request.price());
        product.setActive(request.activeOr(activeWhenAbsent));
        product.setCategory(categoryRepository.getReferenceById(categoryRef.id()));
        product.setSpecies(speciesRepository.getReferenceById(speciesRef.id()));
        product.setBrand(brandRepository.getReferenceById(brandRef.id()));

        try {
            productRepository.saveAndFlush(product);
        } catch (DataIntegrityViolationException e) {
            throw translate(e, product.getSku());
        }

        return ProductResponse.of(product, ReferenceResponse.of(categoryRef),
                ReferenceResponse.of(speciesRef), ReferenceResponse.of(brandRef));
    }

    private static void checkSortable(Sort sort) {
        sort.forEach(order -> {
            if (!SORTABLE.contains(order.getProperty())) {
                throw new IllegalArgumentException("Cannot sort by " + order.getProperty()
                        + "; sortable properties: " + String.join(", ", SORTABLE));
            }
        });
    }

    private static RuntimeException translate(DataIntegrityViolationException e, String sku) {
        String cause = String.valueOf(e.getMostSpecificCause().getMessage());
        return cause.contains(SKU_INDEX) ? duplicateSku(sku, e) : e;
    }

    private static IllegalArgumentException duplicateSku(String sku) {
        return duplicateSku(sku, null);
    }

    private static IllegalArgumentException duplicateSku(String sku, Throwable cause) {
        return new IllegalArgumentException("Product with sku " + sku + " already exists", cause);
    }
}
