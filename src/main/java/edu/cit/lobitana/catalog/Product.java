package edu.cit.lobitana.catalog;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A thing this shop sells. Knows nothing about any sales channel or supplier protocol. */
@Entity
@Table(name = "products")
public class Product {

    @Id
    private String sku;

    private String name;

    protected Product() {
        // for JPA
    }

    public Product(String sku, String name) {
        this.sku = sku;
        this.name = name;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
