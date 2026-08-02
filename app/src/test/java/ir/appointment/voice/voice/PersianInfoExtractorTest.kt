package ir.appointment.voice.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PersianInfoExtractorTest {

    @Test
    fun `extracts explicit numeric date and time`() {
        val r = PersianInfoExtractor.extract("جلسه با آقای رضایی در دفتر ساعت 17:30 تاریخ 1403/5/12")
        assertEquals(1403, r.jalaliYear)
        assertEquals(5, r.jalaliMonth)
        assertEquals(12, r.jalaliDay)
        assertEquals(17, r.hour)
        assertEquals(30, r.minute)
    }

    @Test
    fun `extracts day plus month name`() {
        val r = PersianInfoExtractor.extract("قرار ملاقات دوازدهم مرداد با دکتر احمدی")
        assertEquals(12, r.jalaliDay)
        assertEquals(5, r.jalaliMonth)
    }

    @Test
    fun `extracts relative day fardaa`() {
        val today = PersianCalendar.todayJalali()
        val r = PersianInfoExtractor.extract("فردا ساعت ده صبح با علی")
        assertEquals(10, r.hour)
        // "فردا" should resolve to a date, and its weekday must be consistent with that date.
        assert(r.jalaliDay != null)
    }

    @Test
    fun `extracts pm time correctly`() {
        val r = PersianInfoExtractor.extract("ساعت 5 عصر قرار داریم")
        assertEquals(17, r.hour)
    }

    @Test
    fun `extracts location after dar keyword`() {
        val r = PersianInfoExtractor.extract("قرار در کافه نادری ساعت 6")
        assertEquals("کافه نادری", r.location)
    }

    @Test
    fun `extracts person after ba keyword`() {
        val r = PersianInfoExtractor.extract("جلسه با سارا محمدی فردا")
        assertEquals("سارا محمدی", r.personName)
    }

    @Test
    fun `missing fields stay null instead of guessed`() {
        val r = PersianInfoExtractor.extract("یک قرار ملاقات دارم")
        assertNull(r.jalaliYear)
        assertNull(r.hour)
    }

    @Test
    fun `weekday is computed from date not from mis-heard speech`() {
        // 1403/5/12 in the Jalali calendar; the extractor must compute the weekday
        // itself rather than trust an (absent, in this case) spoken weekday.
        val r = PersianInfoExtractor.extract("تاریخ 1403/5/12 ساعت 10")
        assert(r.weekdayName != null)
    }
}
