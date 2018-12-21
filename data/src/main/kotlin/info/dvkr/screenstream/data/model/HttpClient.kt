package info.dvkr.screenstream.data.model

import android.os.Parcelable
import kotlinx.android.parcel.Parcelize

@Parcelize
data class HttpClient(
    val id: Long,
    val clientAddress: String,
    val isSlowConnection: Boolean,
    val isDisconnected: Boolean
) : Parcelable