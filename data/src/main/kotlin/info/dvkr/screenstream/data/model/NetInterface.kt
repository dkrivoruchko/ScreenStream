package info.dvkr.screenstream.data.model

import android.os.Parcelable
import androidx.annotation.Keep
import kotlinx.android.parcel.Parcelize
import java.net.Inet4Address

@Keep @Parcelize
data class NetInterface(
    val name: String,
    val address: Inet4Address
) : Parcelable