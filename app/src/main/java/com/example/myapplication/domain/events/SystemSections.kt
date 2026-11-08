package com.example.myapplication.domain.events

/**
 * Системные разделы (TECHNICAL_SPEC.md, 5.4). Они не создаются событиями, а входят в стартовое
 * состояние свёртки: на каждом устройстве одинаковые uuid, поэтому события, адресованные им, принимаются везде.
 */
object SystemSections {

    val all: List<SectionState> = listOf(
        SectionState(uuid = "section-food", name = "Продукты", iconKey = "food", sortOrder = 1, hidden = false, deleted = false),
        SectionState(uuid = "section-chemistry", name = "Химия", iconKey = "chemistry", sortOrder = 2, hidden = false, deleted = false),
        SectionState(uuid = "section-pills", name = "Таблетки", iconKey = "pills", sortOrder = 3, hidden = false, deleted = false),
    )

    val uuids: Set<String> = all.map { it.uuid }.toSet()
}
