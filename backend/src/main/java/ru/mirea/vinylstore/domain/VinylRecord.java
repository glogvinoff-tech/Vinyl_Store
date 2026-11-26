package ru.mirea.vinylstore.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/** Виниловая пластинка, на которую ссылаются карточки заказов. */
@Entity
@Table(name = "vinyl_record")
public class VinylRecord extends AuditedVersionedEntity {
    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Column(length = 200)
    private String name;

    @Column(name = "release_year")
    private Integer releaseYear;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable=false,length=120)
    private String artist = "";
    public String getArtist() { return artist; }
    public void setArtist(String value) { artist = value; }
    @Column(length=50)
    private String genre = "Rock";
    public String getGenre() { return genre; }
    public void setGenre(String value) { genre = value; }
    @Column(nullable=false,precision=10,scale=2)
    private java.math.BigDecimal price = new java.math.BigDecimal("4990");
    public java.math.BigDecimal getPrice() { return price; }
    public void setPrice(java.math.BigDecimal value) { price = value; }
    @Column(nullable=false)
    private int stock = 10;
    public int getStock() { return stock; }
    public void setStock(int value) { stock = value; }
    @Column(length=200)
    private String cover = "/assets/1-imgImage50.png";
    public String getCover() { return cover; }
    public void setCover(String value) { cover = value; }
    @Column(length=2000)
    private String description = "";
    public String getDescription() { return description; }
    public void setDescription(String value) { description = value; }

    @Column(nullable=false, length=1100)
    private String categories = "";
    public String getCategories() { return categories; }
    public void setCategories(String value) { categories = value; }
    @Column(nullable=false, length=2000)
    private String images = "";
    public java.util.List<String> getImages() { return images.isBlank() ? java.util.List.of() : java.util.List.of(images.split(",")); }
    public void setImages(java.util.List<String> value) { images = String.join(",", value); }
    @Column(nullable=false)
    private int discountPercent;
    public int getDiscountPercent() { return discountPercent; }
    public void setDiscountPercent(int value) { discountPercent = value; }
    public java.math.BigDecimal getSalePrice() {
        return price.multiply(java.math.BigDecimal.valueOf(100 - discountPercent))
            .divide(java.math.BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
    }

    protected VinylRecord() {
    }

    public VinylRecord(UUID id, String code, String name, Integer releaseYear) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.releaseYear = releaseYear;
    }

    public UUID getId() { return id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getReleaseYear() { return releaseYear; }
    public void setReleaseYear(Integer releaseYear) { this.releaseYear = releaseYear; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
