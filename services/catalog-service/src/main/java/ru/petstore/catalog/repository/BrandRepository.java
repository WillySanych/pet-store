package ru.petstore.catalog.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.petstore.catalog.domain.Brand;

public interface BrandRepository extends JpaRepository<Brand, Long> {
}
