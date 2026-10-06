package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SalaryPdfPresentationTableTest {
    @Test fun employerSubtotalsArePreservedOnceOutsideContributions() {
        val after = "Sous-total patronal connu après réductions" to "À confirmer"
        val before = "Sous-total patronal connu avant réductions" to "À confirmer"
        val tables = SalaryExamplePdfV2.presentationSections(listOf(
            SalaryExamplePdfV2.PdfSection("ESTIMATION DE RÉMUNÉRATION", listOf(after)),
            SalaryExamplePdfV2.PdfSection("COTISATIONS — SALARIÉ / EMPLOYEUR", listOf(before, after))))
        val employer = tables.single { it.name == "SYNTHÈSE EMPLOYEUR" }
        assertEquals(1, employer.rows.count { it.first() == before.first })
        assertEquals(1, employer.rows.count { it.first() == after.first })
        assertTrue(tables.single { it.name == "COTISATIONS — SALARIÉ / EMPLOYEUR" }.rows.isEmpty())
    }
    @Test fun contributionColumnsPreserveAmountsAndUnknownBases() {
        assertEquals(listOf("Retraite", "À confirmer", "À confirmer", "123,45 €", "À confirmer", "456,78 €"),
            SalaryExamplePdfV2.contributionCells("Retraite", "123,45 € / 456,78 €"))
        assertEquals("À confirmer", SalaryExamplePdfV2.contributionCells("AGS (employeur)", "À confirmer").last())
        assertEquals("—", SalaryExamplePdfV2.contributionCells("AGS (employeur)", "À confirmer")[3])
    }

    @Test fun presentationSeparatesAndOrdersSalaryWithoutChangingValues() {
        val rows = listOf("Brut social estimé AGKGMG hors paniers" to "2000 €",
            "Paniers hors brut" to "5 × 10 € = 50 €", "Net estimé avant impôt" to "1500 €")
        val tables = SalaryExamplePdfV2.presentationSections(listOf(
            SalaryExamplePdfV2.PdfSection("ESTIMATION DE RÉMUNÉRATION", rows),
            SalaryExamplePdfV2.PdfSection("COTISATIONS — SALARIÉ / EMPLOYEUR", listOf("Retraite" to "100 € / 200 €"))))
        assertEquals(listOf("RÉMUNÉRATION BRUTE", "COTISATIONS — SALARIÉ / EMPLOYEUR",
            "PANIERS ET FRAIS", "SYNTHÈSE DU NET", "FIABILITÉ DES COTISATIONS"), tables.map { it.name })
        assertTrue(tables.flatMap { it.rows }.contains(listOf("Paniers hors brut", "5 × 10 € = 50 €")))
    }

    @Test fun wrappingAndPaginationKeepEveryCellInOrder() {
        val original = SalaryExamplePdfV2.PresentationTable("COTISATIONS", listOf("A", "B"),
            listOf(300f, 239f), listOf(listOf("a|b|c", "d|e")))
        val wrapped = SalaryExamplePdfV2.wrapTableRows(original) { text, _ -> text.split('|') }
        assertEquals(listOf(listOf("a", "d"), listOf("b", "e"), listOf("c", "")), wrapped.rows)
        val pages = SalaryExamplePdfV2.paginateSections(listOf(SalaryExamplePdfV2.PdfSection(
            wrapped.name, wrapped.rows.indices.map { it.toString() to "" })),
            contentTop = 0f, contentBottom = 70f, sectionHeaderHeight = 42f,
            rowHeight = 14f, sectionTailHeight = 14f)
        assertEquals(listOf("0", "1", "2"), pages.flatMap { it.fragments }.flatMap { it.lines }.map { it.first })
        assertEquals(3, pages.size)
    }
    @Test fun flowingTablesUseRemainingPageSpaceAndPreserveRows() {
        val sections = listOf(
            SalaryExamplePdfV2.PdfSection("BRUT", listOf("first" to "value")),
            SalaryExamplePdfV2.PdfSection("COTISATIONS", (0..3).map { "row$it" to "amount$it" }))
        val pages = SalaryExamplePdfV2.paginateSections(sections, contentTop = 0f,
            contentBottom = 150f, sectionHeaderHeight = 42f, rowHeight = 14f,
            sectionTailHeight = 14f, keepSectionsTogether = false)
        assertEquals(2, pages.first().fragments.size)
        assertEquals(listOf("first", "row0", "row1", "row2", "row3"),
            pages.flatMap { it.fragments }.flatMap { it.lines }.map { it.first })
        assertTrue(pages.last().fragments.first().continuation)
    }

}
