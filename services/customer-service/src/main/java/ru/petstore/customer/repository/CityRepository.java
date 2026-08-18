package ru.petstore.customer.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.petstore.customer.domain.City;

public interface CityRepository extends JpaRepository<City, Long> {
}
