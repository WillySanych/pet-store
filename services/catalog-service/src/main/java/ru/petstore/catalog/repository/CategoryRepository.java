package ru.petstore.catalog.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.petstore.catalog.domain.Category;

public interface CategoryRepository extends JpaRepository<Category, Long> {
}
