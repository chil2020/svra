package io.svra.note;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

/** 一次抽取＝某個模型跑一次的結果。換模型重跑會新增一筆，舊的保留供比較。 */
@Entity
@Table(name = "note_extractions")
public class NoteExtraction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "note_id", nullable = false, updatable = false)
    private Long noteId;

    @Column(nullable = false, length = 64)
    private String model;

    @Column(name = "prompt_version", nullable = false, length = 32)
    private String promptVersion;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 推播出去的 LINE 訊息 ID，用來對應使用者的引用回覆。 */
    @Column(name = "notify_message_id", length = 64)
    private String notifyMessageId;

    @OneToMany(mappedBy = "extraction", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<NoteItem> items = new ArrayList<>();

    protected NoteExtraction() {
    }

    private NoteExtraction(Long noteId, String model, String promptVersion) {
        this.noteId = noteId;
        this.model = model;
        this.promptVersion = promptVersion;
        this.active = true;
        this.createdAt = Instant.now();
    }

    public static NoteExtraction of(Long noteId, String model, String promptVersion) {
        return new NoteExtraction(noteId, model, promptVersion);
    }

    public void addItem(NoteItem item) {
        items.add(item);
        item.attachTo(this);
    }

    /** orphanRemoval = true，從集合移除就等於刪除該列。 */
    public void removeItem(NoteItem item) {
        items.remove(item);
    }

    public void recordNotified(String lineMessageId) {
        this.notifyMessageId = lineMessageId;
    }

    /** 舊版本停用。DB 有部分唯一索引擋著，同一則 note 不會有兩個生效版本。 */
    public void deactivate() {
        this.active = false;
    }

    public Long getId() { return id; }
    public Long getNoteId() { return noteId; }
    public String getModel() { return model; }
    public String getPromptVersion() { return promptVersion; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
    public List<NoteItem> getItems() { return items; }

    /**
     * 依顯示順序排好的項目。**要編號給使用者看、或要把編號解回項目，都用這個**，
     * 不要用 {@link #getItems()}——那是 JPA 給什麼就是什麼，順序不保證。
     */
    public List<NoteItem> getOrderedItems() {
        return items.stream().sorted(NoteCategory.itemOrder()).toList();
    }
    public String getNotifyMessageId() { return notifyMessageId; }
}
