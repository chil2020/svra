package io.svra.extract;

import java.time.Instant;
import java.util.List;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "note_items")
public class NoteItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "extraction_id", nullable = false)
    private NoteExtraction extraction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private NoteCategory category;

    @Column(nullable = false, columnDefinition = "text")
    private String title;

    @Column(name = "occurs_at")
    private Instant occursAt;

    @Column(columnDefinition = "text")
    private String detail;

    /** PostgreSQL 的 text[]，用 Hibernate 6 的 JdbcTypeCode 對應。 */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]")
    private List<String> tags;

    protected NoteItem() {
    }

    public NoteItem(NoteCategory category, String title, Instant occursAt, String detail, List<String> tags) {
        this.category = category;
        this.title = title;
        this.occursAt = occursAt;
        this.detail = detail;
        this.tags = tags;
    }

    public void rename(String newTitle) {
        this.title = newTitle;
    }

    public void reschedule(Instant newOccursAt) {
        this.occursAt = newOccursAt;
    }

    void attachTo(NoteExtraction extraction) {
        this.extraction = extraction;
    }

    public Long getId() { return id; }
    public NoteCategory getCategory() { return category; }
    public String getTitle() { return title; }
    public Instant getOccursAt() { return occursAt; }
    public String getDetail() { return detail; }
    public List<String> getTags() { return tags; }
}
