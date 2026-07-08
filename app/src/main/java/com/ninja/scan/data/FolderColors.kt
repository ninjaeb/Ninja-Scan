package com.ninja.scan.data

/**
 * Same 10-color palette as business card tags (res/values/colors.xml's
 * tag_* colors), assigned round-robin as new folders are created so
 * folders get a consistent, distinguishable look without a manual picker.
 */
object FolderColors {
    val PALETTE = listOf(
        "#EF5350", "#FFA726", "#FFCA28", "#66BB6A", "#26A69A",
        "#42A5F5", "#29B6F6", "#AB47BC", "#7E57C2", "#EC407A",
    )

    fun next(existingCount: Int): String = PALETTE[existingCount % PALETTE.size]
}
