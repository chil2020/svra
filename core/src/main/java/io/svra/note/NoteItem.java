package io.svra.note;

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

    /**
     * {@code occursAt} 裡的時刻是不是使用者自己講的。
     *
     * <p>🔴 <b>可以是 null，而那是三種狀態不是兩種</b>：true＝他說了幾點，
     * false＝他只說了日期、09:00 是抽取規則補的，null＝沒有時間可言，
     * <b>或是 v8 之前留下來的舊資料</b>。
     *
     * <p>清單排版不在乎這個欄位（都印同一種格式），只有匯進 Google 行事曆時
     * 才分得出差別：true 是定時事件，false 是全天事件。見決策 26。
     */
    @Column(name = "time_specified")
    private Boolean timeSpecified;

    /**
     * 這一筆在 Google 行事曆上的事件 id；{@code null} 代表還沒匯入過。
     *
     * <p>id 本身是從 {@code this.id} 決定性推算出來的（見 {@code CalendarEventIds}），
     * 所以嚴格說它是冗餘的。存下來是為了兩件事：<b>當「匯入過沒有」的旗標</b>
     * （卡片上的按鈕文字要靠它決定），以及萬一哪天推算規則變了，舊資料還指得回原來那筆。
     */
    @Column(name = "google_event_id", length = 128)
    private String googleEventId;

    @Column(columnDefinition = "text")
    private String detail;

    /** PostgreSQL 的 text[]，用 Hibernate 6 的 JdbcTypeCode 對應。 */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]")
    private List<String> tags;

    protected NoteItem() {
    }

    public NoteItem(NoteCategory category, String title, Instant occursAt,
            Boolean timeSpecified, String detail, List<String> tags) {
        this.category = category;
        this.title = title;
        this.occursAt = occursAt;
        this.timeSpecified = occursAt == null ? null : timeSpecified;
        this.detail = detail;
        this.tags = tags;
    }

    public void rename(String newTitle) {
        this.title = newTitle;
    }

    /**
     * 改時間。
     *
     * <p>{@code timeSpecified} 跟著改：使用者說「改成下午三點」時他講了時刻，
     * 說「改到星期五」時沒有。<b>不跟著改的話，一筆原本的全天事件會在改成
     * 三點之後，仍然以全天的形式同步回行事曆。</b>
     */
    public void reschedule(Instant newOccursAt, Boolean newTimeSpecified) {
        this.occursAt = newOccursAt;
        this.timeSpecified = newOccursAt == null ? null : newTimeSpecified;
    }

    /** 匯入行事曆之後記下事件 id；{@code null} 代表回到「沒匯入過」。 */
    public void markCalendarEvent(String googleEventId) {
        this.googleEventId = googleEventId;
    }

    void attachTo(NoteExtraction extraction) {
        this.extraction = extraction;
    }

    public Long getId() { return id; }
    public NoteExtraction getExtraction() { return extraction; }
    public NoteCategory getCategory() { return category; }
    public String getTitle() { return title; }
    public Instant getOccursAt() { return occursAt; }
    public Boolean getTimeSpecified() { return timeSpecified; }
    public String getGoogleEventId() { return googleEventId; }
    public String getDetail() { return detail; }
    public List<String> getTags() { return tags; }
}
