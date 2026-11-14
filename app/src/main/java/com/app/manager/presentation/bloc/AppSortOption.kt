package com.app.manager.presentation.bloc

/**
 * How the app list is ordered.
 *
 * Catalog order is fine for a small list, but "what changed recently" and "find it alphabetically"
 * are the two other ways people actually look. RECENTLY_UPDATED sorts by the catalog's build date
 * (`updatedAt`, ported with the patch-update feature); entries without a date keep catalog order.
 */
enum class AppSortOption {
    /** The order the catalog itself lists apps in — curated, so it stays the default. */
    CATALOG,

    /** Newest build first. */
    RECENTLY_UPDATED,

    NAME_ASC
}
