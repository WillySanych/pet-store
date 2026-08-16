package ru.petstore.catalog.repository;

import org.springframework.data.jpa.domain.Specification;
import ru.petstore.catalog.domain.Product;

/**
 * Optional filters for the product listing.
 */
public final class ProductSpecifications {

    private ProductSpecifications() {
    }

    public static Specification<Product> referenceIs(String attribute, Long id) {
        return id == null
                ? Specification.unrestricted()
                : (root, query, cb) -> cb.equal(root.get(attribute).get("id"), id);
    }

    public static Specification<Product> activeIs(Boolean active) {
        return active == null
                ? Specification.unrestricted()
                : (root, query, cb) -> cb.equal(root.get("active"), active);
    }
}
