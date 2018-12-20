package info.dvkr.screenstream.data.model

import android.os.Parcelable
import kotlinx.android.parcel.Parcelize
import java.net.Inet4Address

@Parcelize
data class NetInterface(
    val name: String,
    val address: Inet4Address
) : Parcelable