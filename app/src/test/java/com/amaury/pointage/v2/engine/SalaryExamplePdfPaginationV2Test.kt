package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SalaryExamplePdfPaginationV2Test {
    private fun section(name: String, rows: Int) = SalaryExamplePdfV2.PdfSection(
        name = name,
        lines = (1..rows).map { index -> "$name-$index" to "value-$index" }
    )

    private fun height(
        page: SalaryExamplePdfV2.PdfPagePlan,
        header: Float,
        row: Float,
        tail: Float
    ): Float = page.fragments.sumOf { fragment ->
        (header + fragment.lines.size * row + tail).toDouble()
    }.toFloat()

    @Test
    fun denseDefaultContentSpansPagesWithoutLosingOrReorderingRows() {
        val sections = listOf(
            section("EMPLOYEUR", 3),
            section("CONTRAT", 4),
            section("TEMPS", 4),
            section("PAUSES", 2),
            section("ESTIMATION", 11),
            section("COMPTEURS", 60),
            section("SOURCES", 9)
        )

        val pages = SalaryExamplePdfV2.paginateSections(sections)

        assertTrue(pages.size >= 2)
        pages.forEach { page ->
            assertTrue(height(page, header = 15f, row = 14f, tail = 23f) <= 690f)
            assertTrue(page.fragments.isNotEmpty())
            assertTrue(page.fragments.all { it.lines.isNotEmpty() })
        }
        val expected = sections.flatMap { source -> source.lines.map { source.name to it } }
        val actual = pages.flatMap { page ->
            page.fragments.flatMap { fragment -> fragment.lines.map { fragment.name to it } }
        }
        assertEquals(expected, actual)
    }

    @Test
    fun sectionThatFitsOnBlankPageMovesWholeInsteadOfSplitting() {
        val pages = SalaryExamplePdfV2.paginateSections(
            sections = listOf(section("FIRST", 4), section("SECOND", 5)),
            contentTop = 0f,
            contentBottom = 100f,
            sectionHeaderHeight = 10f,
            rowHeight = 10f,
            sectionTailHeight = 10f
        )

        assertEquals(2, pages.size)
        assertEquals(listOf("FIRST"), pages[0].fragments.map { it.name })
        assertEquals(listOf("SECOND"), pages[1].fragments.map { it.name })
        assertEquals(5, pages[1].fragments.single().lines.size)
        assertFalse(pages[1].fragments.single().continuation)
    }

    @Test
    fun oversizedSectionSplitsOnlyBetweenRowsAndMarksContinuationPages() {
        val original = section("LONG", 9)

        val pages = SalaryExamplePdfV2.paginateSections(
            sections = listOf(original),
            contentTop = 0f,
            contentBottom = 60f,
            sectionHeaderHeight = 10f,
            rowHeight = 10f,
            sectionTailHeight = 10f
        )

        assertEquals(3, pages.size)
        assertEquals(listOf(4, 4, 1), pages.map { it.fragments.single().lines.size })
        assertEquals(listOf(false, true, true), pages.map { it.fragments.single().continuation })
        assertEquals(original.lines, pages.flatMap { it.fragments.single().lines })
        assertTrue(pages.all { it.fragments.single().name == "LONG" })
    }

    @Test
    fun exactBottomBoundaryFitsAndOneExtraRowStartsAnotherPage() {
        fun pagesFor(rows: Int) = SalaryExamplePdfV2.paginateSections(
            sections = listOf(section("LIMIT", rows)),
            contentTop = 0f,
            contentBottom = 60f,
            sectionHeaderHeight = 10f,
            rowHeight = 10f,
            sectionTailHeight = 10f
        )

        assertEquals(1, pagesFor(4).size)
        assertEquals(2, pagesFor(5).size)
    }

    @Test
    fun emptyContentStillProducesOneHeaderFooterPage() {
        val pages = SalaryExamplePdfV2.paginateSections(emptyList())

        assertEquals(1, pages.size)
        assertTrue(pages.single().fragments.isEmpty())
    }
}
