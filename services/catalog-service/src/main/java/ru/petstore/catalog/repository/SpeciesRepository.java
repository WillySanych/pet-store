package ru.petstore.catalog.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.petstore.catalog.domain.Species;

public interface SpeciesRepository extends JpaRepository<Species, Long> {
}
