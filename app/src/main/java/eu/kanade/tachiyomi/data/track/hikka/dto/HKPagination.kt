package eu.kanade.tachiyomi.data.track.hikka.dto

import kotlinx.serialization.Serializable

/**
 * Разбивка ответа Hikka.
 *
 * Все поля с дефолтами: сервер отдаёт неполную пагинацию (например, без
 * `pages` на пустой выдаче), а раньше такое приводило к падению разбора
 * всего ответа — на экране манги появлялась ошибка вместо трекера.
 */
@Serializable
data class HKPagination(
    val total: Int = 0,
    val pages: Int = 0,
    val page: Int = 0,
)
