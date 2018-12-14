package info.dvkr.screenstream.data.model

import android.os.Parcelable
import androidx.annotation.Keep
import kotlinx.android.parcel.Parcelize

@Keep @Parcelize
data class TrafficPoint(
    val time: Long,
    val bytes: Long
) : Parcelable