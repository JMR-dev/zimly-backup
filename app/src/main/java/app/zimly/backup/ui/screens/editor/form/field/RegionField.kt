package app.zimly.backup.ui.screens.editor.form.field

import io.minio.Utils

/**
 * Optional field for an S3 region. Uses [Utils.REGION_REGEX] to validate a given region, which
 * minio will use internally as well. `null` value will validate as well (optional).
 */
class RegionField(
    errorMessage: String = "Not a valid region.",
    validate: (value: String?) -> Boolean = {
        it?.let { Utils.REGION_REGEX.matcher(it).find() } ?: true
    },
    defaultValue: String? = null,
    initialValidation: Boolean = true

) : BaseField<String?>(errorMessage, validate, defaultValue, initialValidation)