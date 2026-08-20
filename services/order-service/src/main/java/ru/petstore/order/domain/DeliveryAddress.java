package ru.petstore.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public class DeliveryAddress {

    @Column(name = "address_city_code", nullable = false)
    private String cityCode;

    @Column(name = "address_city_name", nullable = false)
    private String cityName;

    @Column(name = "address_street", nullable = false)
    private String street;

    @Column(name = "address_building", nullable = false)
    private String building;

    @Column(name = "address_apartment")
    private String apartment;

    @Column(name = "address_postal_code")
    private String postalCode;

    protected DeliveryAddress() {
    }

    public DeliveryAddress(String cityCode, String cityName, String street, String building,
                           String apartment, String postalCode) {
        this.cityCode = cityCode;
        this.cityName = cityName;
        this.street = street;
        this.building = building;
        this.apartment = apartment;
        this.postalCode = postalCode;
    }

    public String getCityCode() {
        return cityCode;
    }

    public String getCityName() {
        return cityName;
    }

    public String getStreet() {
        return street;
    }

    public String getBuilding() {
        return building;
    }

    public String getApartment() {
        return apartment;
    }

    public String getPostalCode() {
        return postalCode;
    }
}
