package com.awesomephoto

import android.content.res.Resources
import androidx.annotation.StringRes

internal class LocalizedFailure(@StringRes val resId: Int, vararg val arguments: Any) : IllegalStateException()

internal fun Throwable.messageFor(resources: Resources): String =
    if (this is LocalizedFailure) resources.getString(resId, *arguments)
    else localizedMessage ?: resources.getString(R.string.unexpected_error)
